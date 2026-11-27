package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for every production trigger installed by DatabaseHelper
 * and the repository schema ensure functions. Android SQLite does not guarantee
 * optional extensions such as JSON1, so trigger SQL must stay within core SQLite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 30, 33, 34, 35])
class TriggerSqlCompatibilityTest {
    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun productionTriggersUseOnlyAndroidCoreSqlFunctions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        val database = DatabaseHelper.getInstance(context).writableDatabase

        val triggerSql = database.rawQuery(
            "SELECT name, sql FROM sqlite_master WHERE type='trigger' ORDER BY name",
            null
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    val sql = cursor.getString(1)
                    assertNotNull("trigger $name must have stored SQL", sql)
                    put(name, sql)
                }
            }
        }

        assertTrue("the production schema must install triggers", triggerSql.isNotEmpty())
        val unsupportedFunction = Regex(
            "\\b(?:json_[a-z0-9_]+|regexp|concat|concat_ws)\\s*\\(",
            RegexOption.IGNORE_CASE
        )
        triggerSql.forEach { (name, sql) ->
            assertFalse(
                "trigger $name contains a non-guaranteed SQLite extension: $sql",
                unsupportedFunction.containsMatchIn(sql)
            )
        }

        // These are the core functions used by the production trigger bodies.
        // Evaluating them on the same Android/Robolectric SQLite connection makes
        // this test fail if a future schema change introduces an unavailable call.
        database.rawQuery(
            """
            SELECT
                COALESCE(NULL, 1),
                EXISTS(SELECT 1),
                LOWER('ABC'),
                REPLACE('a-b', '-', ''),
                TRIM(' x '),
                SUM(value),
                COUNT(value),
                STRFTIME('%Y', '2026-01-01'),
                DATE('2026-01-01', '+1 day'),
                HEX(RANDOMBLOB(16)),
                CHANGES(),
                LAST_INSERT_ROWID(),
                MAX(0, 2),
                CAST(2 AS INTEGER),
                CASE WHEN 1=1 THEN 'ok' ELSE 'bad' END
            FROM (SELECT 1 AS value UNION ALL SELECT 2)
            """.trimIndent(),
            null
        ).use { cursor ->
            assertTrue("core trigger functions must execute", cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals("abc", cursor.getString(2))
            assertEquals("ab", cursor.getString(3))
            assertEquals("x", cursor.getString(4))
            assertEquals(3, cursor.getInt(5))
            assertEquals(2, cursor.getInt(6))
            assertEquals("2026", cursor.getString(7))
            assertEquals("2026-01-02", cursor.getString(8))
            assertEquals(32, cursor.getString(9).length)
            assertTrue(cursor.getInt(10) >= 0)
            assertTrue(cursor.getLong(11) >= 0)
            assertEquals(2, cursor.getInt(12))
            assertEquals(2, cursor.getInt(13))
            assertEquals("ok", cursor.getString(14))
        }
    }
}
