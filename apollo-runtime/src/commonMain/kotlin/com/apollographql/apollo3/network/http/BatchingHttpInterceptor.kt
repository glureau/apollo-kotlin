package com.apollographql.apollo3.network.http

import com.apollographql.apollo3.ApolloCall
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.AnyAdapter
import com.apollographql.apollo3.api.CustomScalarAdapters
import com.apollographql.apollo3.api.ExecutionOptions
import com.apollographql.apollo3.api.Operation
import com.apollographql.apollo3.api.http.HttpBody
import com.apollographql.apollo3.api.http.HttpMethod
import com.apollographql.apollo3.api.http.HttpRequest
import com.apollographql.apollo3.api.http.HttpResponse
import com.apollographql.apollo3.api.http.valueOf
import com.apollographql.apollo3.api.json.BufferedSinkJsonWriter
import com.apollographql.apollo3.api.json.BufferedSourceJsonReader
import com.apollographql.apollo3.api.json.buildJsonByteString
import com.apollographql.apollo3.api.json.writeArray
import com.apollographql.apollo3.exception.ApolloException
import com.apollographql.apollo3.exception.ApolloHttpException
import com.apollographql.apollo3.internal.BackgroundDispatcher
import com.apollographql.apollo3.mpp.ensureNeverFrozen
import com.apollographql.apollo3.mpp.freeze
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer
import okio.BufferedSink
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * An [HttpInterceptor] that batches HTTP queries to execute multiple at once.
 * This reduces the number of HTTP round trips at the price of increased latency as
 * every request in the batch is now as slow as the slowest one.
 * Some servers might have a per-HTTP-call cache making it faster to resolve 1 big array
 * of n queries compared to resolving the n queries separately.
 *
 * Because [com.apollographql.apollo3.ApolloCall.execute] suspends, it only makes sense to use query batching when queries are
 * executed from different coroutines. Use [async] to create a new coroutine if needed
 *
 * [BatchingHttpInterceptor] buffers the whole response, so it might additionally introduce some
 * client-side latency as it cannot amortize parsing/building the models during network I/O.
 *
 * [BatchingHttpInterceptor] only works with Post requests. Trying to batch a Get request is undefined.
 *
 * HTTP headers will be merged from all requests in the batch by keeping the ones that have the same name and value in all requests. Any
 * headers present in only some requests, or with different values in some requests will be dropped.
 *
 * @param batchIntervalMillis the maximum time interval before a new batch is sent
 * @param maxBatchSize the maximum number of requests queued before a new batch is sent
 * @param exposeErrorBody configures whether to expose the error body in [ApolloHttpException].
 *
 * If you're setting this to `true`, you **must** catch [ApolloHttpException] and close the body explicitly
 * to avoid sockets and other resources leaking.
 *
 * Default: false
 */
class BatchingHttpInterceptor @JvmOverloads constructor(
    private val batchIntervalMillis: Long = 10,
    private val maxBatchSize: Int = 10,
    private val exposeErrorBody: Boolean = false,
) : HttpInterceptor {
  private val dispatcher = BackgroundDispatcher()
  private val scope = CoroutineScope(dispatcher.coroutineDispatcher)
  private val mutex = Mutex()
  private var disposed = false

  private val job: Job

  private var interceptorChain: HttpInterceptorChain? = null

  init {
    ensureNeverFrozen(this)
    job = scope.launch {
      while (true) {
        delay(batchIntervalMillis)
        executePendingRequests()
      }
    }
  }

  class PendingRequest(
      val request: HttpRequest,
  ) {
    val deferred = CompletableDeferred<HttpResponse>()
  }

  private val pendingRequests = mutableListOf<PendingRequest>()

  override suspend fun intercept(request: HttpRequest, chain: HttpInterceptorChain): HttpResponse {
    // Batching is enabled by default, unless explicitly disabled
    val canBeBatched = request.headers.valueOf(ExecutionOptions.CAN_BE_BATCHED)?.toBoolean() ?: true

    if (!canBeBatched) {
      // Remove the CAN_BE_BATCHED header and forward directly
      return chain.proceed(request.newBuilder().addHeaders(headers = request.headers.filter { it.name != ExecutionOptions.CAN_BE_BATCHED }).build())
    }

    // Keep the chain for later
    interceptorChain = chain

    val pendingRequest = PendingRequest(request)

    val sendNow = mutex.withLock {
      // if there was an error, the previous job was already canceled, ignore that error
      pendingRequests.add(pendingRequest)
      pendingRequests.size >= maxBatchSize
    }
    if (sendNow) {
      executePendingRequests()
    }

    return pendingRequest.deferred.await()
  }

  private suspend fun executePendingRequests() {
    val pending = mutex.withLock {
      val copy = pendingRequests.toList()
      pendingRequests.clear()
      copy
    }

    if (pending.isEmpty()) {
      return
    }

    val firstRequest = pending.first().request

    val allBodies = pending.map { it.request.body ?: error("empty body while batching queries") }
    // Only keep headers with the same name and value in all requests
    val commonHeaders = pending.map { it.request.headers }.reduce { acc, headers ->
      acc.intersect(headers.toSet()).toList()
    }
        // Also do not send our internal use header
        .filter { it.name != ExecutionOptions.CAN_BE_BATCHED }

    val body = object : HttpBody {
      override val contentType = "application/json"

      // We don't know the combined size at that point.
      // Note: this assumes the underlying HttpEngine will use chunked encoding when contentLength is -1.
      override val contentLength = -1L

      override fun writeTo(bufferedSink: BufferedSink) {
        val writer = BufferedSinkJsonWriter(bufferedSink)
        writer.writeArray {
          this as BufferedSinkJsonWriter
          allBodies.forEach { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            jsonValue(buffer.readUtf8())
          }
        }
      }
    }

    val request = HttpRequest.Builder(
        method = HttpMethod.Post,
        url = firstRequest.url,
    )
        .body(body)
        .headers(commonHeaders)
        .build()

    freeze(request)

    var exception: ApolloException? = null
    val result = try {
      val response = interceptorChain!!.proceed(request)
      if (response.statusCode !in 200..299) {
        val maybeBody = if (exposeErrorBody) {
          response.body
        } else {
          response.body?.close()
          null
        }
        throw ApolloHttpException(
            response.statusCode,
            response.headers,
            maybeBody,
            "HTTP error ${response.statusCode} while executing batched query"
        )
      }
      val responseBody = response.body ?: throw ApolloException("null body when executing batched query")

      // TODO: this is most likely going to transform BigNumbers into strings, not sure how much of an issue that is
      val list = AnyAdapter.fromJson(BufferedSourceJsonReader(responseBody), CustomScalarAdapters.Empty)
      if (list !is List<*>) throw ApolloException("batched query response is not a list when executing batched query")

      if (list.size != pending.size) {
        throw ApolloException("batched query response count (${list.size}) does not match the requested queries (${pending.size})")
      }

      list.map {
        if (it == null) {
          throw ApolloException("batched query response contains a null item")
        }
        (buildJsonByteString {
          AnyAdapter.toJson(this, CustomScalarAdapters.Empty, it)
        })
      }
    } catch (e: Exception) {
      exception = when (e) {
        is ApolloException -> e
        else -> ApolloException("batched query failed with exception", e)
      }
      null
    }

    if (exception != null) {
      pending.forEach {
        it.deferred.completeExceptionally(exception)
      }
      return
    } else {
      result!!.forEachIndexed { index, byteString ->
        // This works because the server must return the responses in order
        pending[index].deferred.complete(
            HttpResponse.Builder(statusCode = 200)
                .body(byteString)
                .build()
        )
      }
    }
  }

  override fun dispose() {
    if (!disposed) {
      interceptorChain = null
      scope.cancel()
      dispatcher.dispose()
      disposed = true
    }
  }

  companion object {
    @JvmStatic
    fun configureApolloClientBuilder(apolloClientBuilder: ApolloClient.Builder, canBeBatched: Boolean) {
      apolloClientBuilder.canBeBatched(canBeBatched)
    }

    @JvmStatic
    fun <D : Operation.Data> configureApolloCall(apolloCall: ApolloCall<D>, canBeBatched: Boolean) {
      apolloCall.canBeBatched(canBeBatched)
    }
  }
}
