import com.apollographql.apollo3.adapter.DateAdapter
import com.apollographql.apollo3.adapter.JavaInstantAdapter
import com.apollographql.apollo3.adapter.JavaLocalDateAdapter
import com.apollographql.apollo3.adapter.JavaLocalDateTimeAdapter
import com.apollographql.apollo3.adapter.JavaOffsetDateTimeAdapter
import com.apollographql.apollo3.adapter.KotlinxInstantAdapter
import com.apollographql.apollo3.adapter.KotlinxLocalDateAdapter
import com.apollographql.apollo3.adapter.KotlinxLocalDateTimeAdapter
import com.apollographql.apollo3.api.Adapter
import com.apollographql.apollo3.api.CustomScalarAdapters
import com.apollographql.apollo3.api.json.BufferedSourceJsonReader
import com.apollographql.apollo3.api.json.buildJsonString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import okio.Buffer
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals

class JavaTimeAdaptersTest {
  private fun String.jsonReader() = BufferedSourceJsonReader(Buffer().writeUtf8("\"${this}\""))

  private fun <T> Adapter<T>.fromJson(string: String): T {
    return fromJson(string.jsonReader(), CustomScalarAdapters.Empty)
  }

  private fun <T> Adapter<T>.toJson(value: T): String {
    return buildJsonString {
      toJson(this, CustomScalarAdapters.Empty, value)
    }.removePrefix("\"")
        .removeSuffix("\"")
  }

  @Test
  fun instant() {
    var instant = JavaInstantAdapter.fromJson("2010-06-01T22:19:44.475Z")
    assertEquals(1275430784475, instant.toEpochMilli())
    assertEquals("2010-06-01T22:19:44.475Z", JavaInstantAdapter.toJson(instant))

    instant = JavaInstantAdapter.fromJson("2010-06-01T23:19:44.475+01:00")
    assertEquals(1275430784475, instant.toEpochMilli())
    // Time zone is lost
    assertEquals("2010-06-01T22:19:44.475Z", JavaInstantAdapter.toJson(instant))
  }

  @Test
  fun offsetDateTime() {
    var offsetDateTime = JavaOffsetDateTimeAdapter.fromJson("2010-06-01T23:19:44.475+01:00")
    assertEquals(1275430784475, offsetDateTime.toInstant().toEpochMilli())
    // Offset is retained
    assertEquals("2010-06-01T23:19:44.475+01:00", JavaOffsetDateTimeAdapter.toJson(offsetDateTime))

    offsetDateTime = JavaOffsetDateTimeAdapter.fromJson("2010-06-01T22:19:44.475Z")
    assertEquals(1275430784475, offsetDateTime.toInstant().toEpochMilli())
    assertEquals("2010-06-01T22:19:44.475Z", JavaOffsetDateTimeAdapter.toJson(offsetDateTime))
  }

  @Test
  fun date() {
    var date = DateAdapter.fromJson("2010-06-01T22:19:44.475Z")
    assertEquals(1275430784475, date.time)
    assertEquals("2010-06-01T22:19:44.475Z", DateAdapter.toJson(date))

    date = DateAdapter.fromJson("2010-06-01T23:19:44.475+01:00")
    assertEquals(1275430784475, date.time)
    // Time zone is lost
    assertEquals("2010-06-01T22:19:44.475Z", DateAdapter.toJson(date))
  }

  @Test
  fun localDateTime() {
    val localDateTime = JavaLocalDateTimeAdapter.fromJson("2010-06-01T22:19:44.475")
    assertEquals(1275430784, localDateTime.toEpochSecond(ZoneOffset.UTC))
    assertEquals("2010-06-01T22:19:44.475", JavaLocalDateTimeAdapter.toJson(localDateTime))
  }

  @Test
  fun localDate() {
    val localDate = JavaLocalDateAdapter.fromJson("2010-06-01")
    assertEquals(1275430784, localDate.atTime(LocalTime.parse("22:19:44.475")).toEpochSecond(ZoneOffset.UTC))
    assertEquals("2010-06-01", JavaLocalDateAdapter.toJson(localDate))
  }

  @Test
  fun kotlinxInstant() {
    var instant = KotlinxInstantAdapter.fromJson("2010-06-01T22:19:44.475Z")
    assertEquals(1275430784475, instant.toEpochMilliseconds())
    assertEquals("2010-06-01T22:19:44.475Z", KotlinxInstantAdapter.toJson(instant))

    instant = KotlinxInstantAdapter.fromJson("2010-06-01T23:19:44.475+01:00")
    assertEquals(1275430784475, instant.toEpochMilliseconds())
    // Time zone is lost
    assertEquals("2010-06-01T22:19:44.475Z", KotlinxInstantAdapter.toJson(instant))
  }

  @Test
  fun kotlinxLocalDateTime() {
    val localDateTime = KotlinxLocalDateTimeAdapter.fromJson("2010-06-01T22:19:44.475")
    assertEquals(1275430784, localDateTime.toInstant(TimeZone.UTC).epochSeconds)
    assertEquals("2010-06-01T22:19:44.475", KotlinxLocalDateTimeAdapter.toJson(localDateTime))
  }

  @Test
  fun kotlinxLocalDate() {
    val localDate = KotlinxLocalDateAdapter.fromJson("2010-06-01")
    assertEquals(1275430784, localDate.atTime(22, 19, 44).toInstant(TimeZone.UTC).epochSeconds)
    assertEquals("2010-06-01", KotlinxLocalDateAdapter.toJson(localDate))
  }

}
