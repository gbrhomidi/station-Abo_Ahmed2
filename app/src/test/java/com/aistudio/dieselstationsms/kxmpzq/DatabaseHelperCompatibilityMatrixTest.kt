package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
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

            val leaseColumns = database.rawQuery("PRAGMA table_info(sms_outbox)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(nameIndex)
                names
            }
            assertTrue("claim timestamp must be persisted", "claimed_at" in leaseColumns)
            assertTrue("lease expiry must be persisted", "lease_until" in leaseColumns)
            assertTrue("attempt token must be persisted", "attempt_token" in leaseColumns)
            assertTrue("claim generation must be persisted", "claim_generation" in leaseColumns)

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
        } finally {
            DatabaseHelper.closeInstance()
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        }
    }
}
