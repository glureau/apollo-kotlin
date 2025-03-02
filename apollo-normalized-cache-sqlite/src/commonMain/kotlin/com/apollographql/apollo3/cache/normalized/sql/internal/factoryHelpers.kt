package com.apollographql.apollo3.cache.normalized.sql.internal

import com.apollographql.apollo3.cache.internal.json.JsonDatabase
import com.apollographql.apollo3.exception.apolloExceptionHandler
import com.squareup.sqldelight.db.SqlDriver
import com.squareup.sqldelight.db.use

internal fun createRecordDatabase(driver: SqlDriver): RecordDatabase {
  maybeCreateOrMigrateSchema(driver, getSchema())

  val tableNames = mutableListOf<String>()

  try {
    // https://sqlite.org/forum/info/d90adfbb0a6eea88
    // The name is sqlite_schema these days but older versions use sqlite_master and sqlite_master is recognized everywhere so use that
    driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name;", 0).use { cursor ->
      while (cursor.next()) {
        tableNames.add(cursor.getString(0) ?: "")
      }
    }
  } catch (e: Exception) {
    apolloExceptionHandler(Exception("An exception occurred while looking up the table names", e))
    /**
     * Best effort: if we can't find any table, open the DB anyway and let's see what's happening
     */
  }

  val expectedTableName ="records"

  check(tableNames.isEmpty() || tableNames.contains(expectedTableName)) {
    "Apollo: Cannot find the '$expectedTableName' table? (found '$tableNames' instead)"
  }

  return JsonRecordDatabase(JsonDatabase(driver).jsonQueries)
}

internal fun getSchema(): SqlDriver.Schema = JsonDatabase.Schema
