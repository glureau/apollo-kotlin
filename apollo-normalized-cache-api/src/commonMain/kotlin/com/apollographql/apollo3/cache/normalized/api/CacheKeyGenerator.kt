package com.apollographql.apollo3.cache.normalized.api

import com.apollographql.apollo3.api.CompiledField
import com.apollographql.apollo3.api.Executable
import com.apollographql.apollo3.api.keyFields

/**
 * An [CacheKeyGenerator] is responsible for finding an id for a given object
 * - takes Json data as input and returns a unique id for an object
 * - is used after a network request
 * - is used during normalization when writing to the cache
 *
 * See also `@typePolicy`
 * See also [CacheResolver]
 */
interface CacheKeyGenerator {
  /**
   * Returns a [CacheKey] for the given object or null if the object doesn't have an id
   *
   * @param obj a [Map] representing the object. The values in the map can have the same types as the ones
   * in [Record]
   * @param context the context in which the object is normalized. In most use cases, the id should not depend on the normalization
   * context. Only use for advanced use cases.
   */
  fun cacheKeyForObject(obj: Map<String, Any?>, context: CacheKeyGeneratorContext): CacheKey?
}

/**
 * The context in which an object is normalized.
 *
 * @param field the field representing the object or for lists, the field representing the list. `field.type` is not
 * always the type of the object. Especially, it can be any combination of [com.apollographql.apollo3.api.CompiledNotNullType]
 * and [com.apollographql.apollo3.api.CompiledListType].
 * Use `field.type.leafType()` to access the type of the object. For interface fields, it will be the interface type,
 * not concrete types.
 * @param variables the variables used in the operation where the object is normalized.
 */
class CacheKeyGeneratorContext(
    val field: CompiledField,
    val variables: Executable.Variables,
)

/**
 * A [CacheKeyGenerator] that uses annotations to compute the id
 */
object TypePolicyCacheKeyGenerator : CacheKeyGenerator {
  override fun cacheKeyForObject(obj: Map<String, Any?>, context: CacheKeyGeneratorContext): CacheKey? {
    val keyFields = context.field.type.leafType().keyFields()

    return if (keyFields.isNotEmpty()) {
      CacheKey(obj["__typename"].toString(), keyFields.map { obj[it].toString() })
    } else {
      null
    }
  }
}
