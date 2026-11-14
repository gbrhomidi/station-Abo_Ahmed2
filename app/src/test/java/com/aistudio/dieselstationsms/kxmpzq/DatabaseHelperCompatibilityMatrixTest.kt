package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class DatabaseHelperCompatibilityMatrixTest {
    @Test
    @Config(sdk = [26, 28, 35])
    fun opensWithForeignKeysAndWalAcrossSupportedAndroidApiLevels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        try {
            val database = DatabaseHelper.getInstance(context).writableDatabase
            assertEquals(DatabaseHelper.VERSION, database.version)
            val foreignKeysEnabled = database.rawQuery("PRAGMA foreign_keys", null).use {
                it.moveToFirst() && it.getInt(0) == 1
            }
            val journalMode = database.rawQuery("PRAGMA journal_mode", null).use {
                assertTrue(it.moveToFirst())
                it.getString(0)
            }
            assertTrue("foreign key enforcement must be enabled", foreignKeysEnabled)
            assertEquals("wal", journalMode.lowercase())

            assertSmsAttemptSchema(database)
        } finally {
            DatabaseHelper.closeInstance()
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        }
    }

    @Test
    @Config(sdk = [26])
    fun upgradesVersion57DatabaseWhenSmsAttemptSchemaIsIncomplete() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        try {
            val database = DatabaseHelper.getInstance(context).writableDatabase
            assertEquals(DatabaseHelper.VERSION, database.version)

            // Re-create the pre-Stage-6 sms_outbox shape and mark it as V57. This models
            // an installation whose version was advanced before the additive columns
            // and attempt tables were durably installed.
            database.beginTransaction()
            try {
                val queuedRows = database.rawQuery("SELECT COUNT(*) FROM sms_outbox", null).use {
                    assertTrue(it.moveToFirst())
                    it.getInt(0)
                }
                assertEquals("migration fixture must not discard user messages", 0, queuedRows)

                database.execSQL("DROP TABLE sms_outbox_attempt_parts")
                database.execSQL("DROP TABLE sms_outbox_attempts")
                database.execSQL("DROP TABLE sms_outbox_late_callbacks")
                database.execSQL("DROP TABLE sms_outbox")
                database.execSQL("""
                    CREATE TABLE sms_outbox (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        message_id TEXT NOT NULL UNIQUE,
                        event_id TEXT,
                        conversation_id TEXT,
                        business_entity_id TEXT,
                        channel TEXT NOT NULL DEFAULT 'sms',
                        provider_message_id TEXT,
                        recipient TEXT NOT NULL,
                        body TEXT NOT NULL,
                        parts_count INTEGER NOT NULL DEFAULT 1,
                        priority TEXT NOT NULL DEFAULT 'NORMAL',
                        status TEXT NOT NULL DEFAULT 'QUEUED',
                        attempt_count INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        queued_at INTEGER NOT NULL,
                        sent_at INTEGER,
                        delivered_at INTEGER,
                        failed_at INTEGER,
                        failure_code TEXT,
                        failure_reason TEXT,
                        subscription_id INTEGER,
                        dedupe_key TEXT NOT NULL UNIQUE,
                        next_attempt_at INTEGER NOT NULL DEFAULT 0,
                        last_part_index INTEGER NOT NULL DEFAULT 0,
                        CHECK(parts_count > 0),
                        CHECK(attempt_count >= 0),
                        CHECK(status IN ('DRAFT','QUEUED','SENDING','SENT','DELIVERY_PENDING','DELIVERED','FAILED','RETRY_PENDING','CANCELLED','EXPIRED'))
                    )
                """.trimIndent())
                database.version = 57
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }

            DatabaseHelper.closeInstance()
            val upgraded = DatabaseHelper.getInstance(context).writableDatabase
            assertEquals("V57 must be upgraded to the current database schema", DatabaseHelper.VERSION, upgraded.version)
            assertSmsAttemptSchema(upgraded)

            // Model an already-installed V59 database with the old BOTH scope, then execute
            // the SQLiteOpenHelper V59->V62 upgrade path, including the V60->V61 supplier additions and V61->V62 receivables ledger.
            upgraded.execSQL(
                """UPDATE payment_terms SET applies_to='both'
                   WHERE station_id IS NULL
                     AND code IN ('AFTER_1','AFTER_3','NET_7','NET_15','NET_30','NET_45','NET_60','NET_90')"""
            )
            upgraded.version = 59
            DatabaseHelper.closeInstance()

            val migratedV61 = DatabaseHelper.getInstance(context).writableDatabase
            assertEquals("V60 must upgrade to the current schema version", DatabaseHelper.VERSION, migratedV61.version)
            assertEquals(DatabaseHelper.VERSION, migratedV61.version)
            assertSmsAttemptSchema(migratedV61)
            val legacyBuiltInScopeCount = migratedV61.rawQuery(
                """SELECT COUNT(*) FROM payment_terms
                   WHERE station_id IS NULL
                     AND code IN ('AFTER_1','AFTER_3','NET_7','NET_15','NET_30','NET_45','NET_60','NET_90')
                     AND applies_to<>'customer'""",
                null
            ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) }
            assertEquals("Every historical built-in payment term must be migrated to customer-only scope", 0, legacyBuiltInScopeCount)

            val supplierNetTerms = migratedV61.rawQuery(
                """SELECT COUNT(*) FROM payment_terms
                   WHERE station_id IS NULL AND applies_to='supplier' AND is_active=1
                     AND code IN ('SUPPLIER_NET_7','SUPPLIER_NET_30')""",
                null
            ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) }
            assertEquals("V60 installs must receive supplier-scoped NET_7 and NET_30 terms", 2, supplierNetTerms)

            val persistentSupplierTasks = migratedV61.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='supplier_invoice_tasks'",
                null
            ).use { it.moveToFirst() }
            assertTrue("V60 installs must receive the persistent supplier task table", persistentSupplierTasks)
        } finally {
            DatabaseHelper.closeInstance()
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        }
    }

    private fun assertSmsAttemptSchema(database: SQLiteDatabase) {
        listOf(
            "claimed_at" to "claim timestamp must be persisted",
            "lease_until" to "lease expiry must be persisted",
            "attempt_token" to "attempt token must be persisted",
            "claim_generation" to "claim generation must be persisted"
        ).forEach { (column, message) ->
            val exists = database.rawQuery(
                "SELECT 1 FROM pragma_table_info('sms_outbox') WHERE name = ? LIMIT 1",
                arrayOf(column)
            ).use { it.moveToFirst() }
            assertTrue(message, exists)
        }

        val attemptTables = listOf(
            "sms_outbox_attempts",
            "sms_outbox_attempt_parts",
            "sms_outbox_late_callbacks"
        )
        attemptTables.forEach { table ->
            val exists = database.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(table)
            ).use { it.moveToFirst() }
            assertTrue("required SMS attempt table $table must be migrated", exists)
        }

        val integrity = database.rawQuery("PRAGMA integrity_check", null).use {
            assertTrue(it.moveToFirst())
            it.getString(0)
        }
        assertEquals("ok", integrity)
        val foreignKeyViolations = database.rawQuery("PRAGMA foreign_key_check", null).use {
            it.count
        }
        assertEquals("new SMS attempt relations must have no foreign key violations", 0, foreignKeyViolations)
    }

}
