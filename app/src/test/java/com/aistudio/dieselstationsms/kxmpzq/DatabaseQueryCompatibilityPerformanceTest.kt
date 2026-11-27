package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis

/**
 * Compatibility and smoke-performance checks for ordinary repository SQL.
 * Trigger SQL is covered separately by TriggerSqlCompatibilityTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseQueryCompatibilityPerformanceTest {
    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun ordinaryQueriesCompileAndUseTheExpectedIndexesOnLatestAndroidApi() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        val database = DatabaseHelper.getInstance(context).writableDatabase

        repeat(250) { index ->
            database.insertOrThrow("audit_logs", null, ContentValues().apply {
                put("uuid", "query-compat-$index")
                put("action_type", if (index % 2 == 0) "READ" else "UPDATE")
                put("table_name", "compatibility_probe")
                put("record_id", index + 1L)
            })
        }

        val plan = database.rawQuery(
            "EXPLAIN QUERY PLAN SELECT id FROM audit_logs WHERE uuid=?",
            arrayOf("query-compat-125")
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.columnCount - 1))
            }.joinToString(" | ")
        }
        assertTrue("uuid lookup should use its unique index; plan=$plan", plan.contains("USING", ignoreCase = true))

        val elapsedMs = measureTimeMillis {
            repeat(250) { index ->
                database.rawQuery(
                    "SELECT id, action_type, table_name, record_id FROM audit_logs WHERE uuid=?",
                    arrayOf("query-compat-$index")
                ).use { cursor ->
                    assertTrue("ordinary parameterized lookup must return a row", cursor.moveToFirst())
                    assertEquals(index + 1L, cursor.getLong(3))
                }
            }
        }
        assertTrue("indexed lookups became unexpectedly slow: ${elapsedMs}ms", elapsedMs < 3_000L)

        val integrity = database.rawQuery("PRAGMA integrity_check", null).use {
            assertTrue(it.moveToFirst())
            it.getString(0).lowercase()
        }
        assertEquals("ok", integrity)
        assertFalse(
            "ordinary query exercise must not create foreign-key violations",
            database.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() }
        )
    }

    @Test
    fun ordinaryAggregateDateAndSchemaQueriesUseAndroidCoreSql() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        val database = DatabaseHelper.getInstance(context).readableDatabase

        database.rawQuery(
            """
            SELECT COUNT(*), COALESCE(SUM(rootpage), 0),
                   STRFTIME('%Y-%m-%d', '2026-11-27'),
                   DATE('2026-11-27', '+7 day'),
                   CAST(COUNT(*) AS INTEGER)
            FROM sqlite_master
            WHERE type IN ('table', 'index', 'view')
            """.trimIndent(),
            null
        ).use { cursor ->
            assertTrue("schema aggregate query must compile on Android SQLite", cursor.moveToFirst())
            assertTrue(cursor.getInt(0) > 0)
            assertTrue(cursor.getLong(1) > 0L)
            assertEquals("2026-11-27", cursor.getString(2))
            assertEquals("2026-12-04", cursor.getString(3))
            assertTrue(cursor.getInt(4) > 0)
        }
    }
}
