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
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/**
 * Progressive database-load benchmark. It reports batch-insert latency and
 * indexed lookup latency for 10k and 50k rows without imposing a machine-specific
 * hard performance SLA on the normal regression suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseLoadPerformanceTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun progressiveLoadsReportInsertAndLookupP95() {
        listOf(10_000, 50_000).forEach { rowCount ->
            DatabaseHelper.closeInstance()
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
            val database = DatabaseHelper.getInstance(context).writableDatabase
            val batchSize = 1_000
            val batchDurationsMs = mutableListOf<Double>()

            var nextId = 0
            while (nextId < rowCount) {
                val endExclusive = minOf(nextId + batchSize, rowCount)
                val elapsedNs = measureNanoTime {
                    database.beginTransaction()
                    try {
                        while (nextId < endExclusive) {
                            database.insertOrThrow("audit_logs", null, ContentValues().apply {
                                put("uuid", "load-$rowCount-$nextId")
                                put("action_type", if (nextId % 2 == 0) "READ" else "UPDATE")
                                put("table_name", "load_benchmark")
                                put("record_id", nextId + 1L)
                            })
                            nextId++
                        }
                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                }
                batchDurationsMs += elapsedNs / 1_000_000.0
            }

            val persistedCount = database.rawQuery(
                "SELECT COUNT(*) FROM audit_logs WHERE table_name=?",
                arrayOf("load_benchmark")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
            assertEquals(rowCount, persistedCount)

            val plan = database.rawQuery(
                "EXPLAIN QUERY PLAN SELECT id FROM audit_logs WHERE uuid=?",
                arrayOf("load-$rowCount-${rowCount / 2}")
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.columnCount - 1))
                }.joinToString(" | ")
            }
            assertTrue("indexed UUID lookup is required; rows=$rowCount plan=$plan", plan.contains("USING", true))

            // Warm up the Android/Robolectric SQLite connection before sampling.
            repeat(20) { lookup(database, "load-$rowCount-${it % rowCount}", rowCount) }
            val lookupDurationsUs = (0 until 200).map { sample ->
                val key = "load-$rowCount-${(sample * 251) % rowCount}"
                val elapsedNs = measureNanoTime { lookup(database, key, rowCount) }
                elapsedNs / 1_000.0
            }
            val lookupP95Us = percentile95(lookupDurationsUs)
            val insertBatchP95Ms = percentile95(batchDurationsMs)

            val integrity = database.rawQuery("PRAGMA integrity_check", null).use {
                assertTrue(it.moveToFirst())
                it.getString(0).lowercase()
            }
            assertEquals("ok", integrity)
            assertFalse(
                "load test must not create foreign-key violations for $rowCount rows",
                database.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() }
            )

            println(
                "DB_LOAD_BENCHMARK rows=$rowCount " +
                    "insert_batch_p95_ms=${"%.3f".format(insertBatchP95Ms)} " +
                    "lookup_p95_us=${"%.3f".format(lookupP95Us)} " +
                    "batches=${batchDurationsMs.size}"
            )
        }
    }

    private fun lookup(database: android.database.sqlite.SQLiteDatabase, uuid: String, rowCount: Int) {
        database.rawQuery(
            "SELECT id, action_type, table_name, record_id FROM audit_logs WHERE uuid=?",
            arrayOf(uuid)
        ).use { cursor ->
            assertTrue("lookup must return a row at $rowCount rows", cursor.moveToFirst())
            assertEquals("load_benchmark", cursor.getString(2))
        }
    }

    private fun percentile95(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val index = ceil(sorted.size * 0.95).toInt().coerceAtLeast(1) - 1
        return sorted[index]
    }
}
