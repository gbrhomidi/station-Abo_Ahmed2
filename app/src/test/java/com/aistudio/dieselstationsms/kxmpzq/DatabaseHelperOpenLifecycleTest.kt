package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseHelperOpenLifecycleTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun reopeningIsLightweightAndKeepsTheProcessSingletonIdentity() {
        val database = helper.writableDatabase
        val schemaVersion = pragmaInt(database, "schema_version")
        assertEquals(DatabaseHelper.VERSION, database.version)
        DatabaseHelper.closeInstance()
        val reopenedHelper = DatabaseHelper.getInstance(context)
        assertSame(helper, reopenedHelper)

        val startedAt = System.nanoTime()
        repeat(4) {
            val reopened = reopenedHelper.readableDatabase
            assertTrue(reopened.isOpen)
            reopenedHelper.close()
        }
        val averageOpenMs = ((System.nanoTime() - startedAt) / 1_000_000.0) / 4.0
        val finalDatabase = reopenedHelper.readableDatabase
        assertEquals("onOpen must not execute schema DDL", schemaVersion, pragmaInt(finalDatabase, "schema_version"))
        assertTrue("repeated SQLite opens were unexpectedly slow: ${averageOpenMs}ms", averageOpenMs < 1_000.0)
        assertSame(helper, DatabaseHelper.getInstance(context))
    }

    @Test
    fun v48CompatibilityMigrationPreservesExistingSettingsAndCreatesLegacyObjects() {
        val database = helper.writableDatabase
        val marker = "lifecycle_migration_marker"
        database.insertOrThrow("settings", null, ContentValues().apply {
            put("setting_key", marker)
            put("setting_value", "preserved")
            put("setting_type", "string")
            put("description", "upgrade regression marker")
            put("is_editable", 1)
            put("created_at", "2026-11-01 00:00:00")
            put("updated_at", "2026-11-01 00:00:00")
        })
        database.version = 48
        DatabaseHelper.closeInstance()

        val upgraded = helper.writableDatabase
        assertEquals(49, upgraded.version)
        val preserved = upgraded.rawQuery(
            "SELECT setting_value FROM settings WHERE setting_key = ?",
            arrayOf(marker)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        assertEquals("preserved", preserved)
        assertTrue(tableExists(upgraded, "sms_processed_hashes"))
        assertTrue(tableExists(upgraded, "sms_outbound_dedupe"))
        assertTrue(tableExists(upgraded, "notifications"))
    }

    @Test
    fun failedV49MigrationRollsBackSchemaVersionAndEarlierDdl() {
        val database = helper.writableDatabase
        database.execSQL("DROP TABLE IF EXISTS sms_processed_hashes")
        database.execSQL(
            """
            CREATE TRIGGER force_v49_rollback
            BEFORE INSERT ON permissions
            BEGIN
                SELECT RAISE(ABORT, 'forced compatibility migration failure');
            END
            """.trimIndent()
        )
        database.version = 48
        DatabaseHelper.closeInstance()

        var migrationFailed = false
        try {
            helper.writableDatabase
        } catch (_: Exception) {
            migrationFailed = true
        }
        assertTrue("migration failure trigger did not abort onUpgrade", migrationFailed)

        DatabaseHelper.closeInstance()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(DatabaseHelper.DATABASE_NAME).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { persisted ->
            assertEquals("user_version must roll back with the failed DDL transaction", 48, persisted.version)
            assertFalse("DDL before the failure must roll back", tableExists(persisted, "sms_processed_hashes"))
        }
    }

    @Test
    fun backupKeepsTheSharedHelperUsableAndProducesAnIntegrityCheckedSnapshot() {
        val database = helper.writableDatabase
        database.execSQL("PRAGMA wal_autocheckpoint = 0")
        val path = helper.backupDatabase()
        val backup = File(path)
        try {
            assertTrue(backup.exists() && backup.length() > 0L)
            assertSame(helper, DatabaseHelper.getInstance(context))
            assertTrue(helper.readableDatabase.isOpen)
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { snapshot ->
                val integrity = snapshot.rawQuery("PRAGMA integrity_check", null).use {
                    assertTrue(it.moveToFirst())
                    it.getString(0)
                }
                assertEquals("ok", integrity.lowercase())
                assertEquals(DatabaseHelper.VERSION, snapshot.version)
            }
        } finally {
            backup.delete()
            File(path + "-wal").delete()
            File(path + "-shm").delete()
            helper.writableDatabase.execSQL("PRAGMA wal_autocheckpoint = 1000")
        }
    }

    private fun pragmaInt(database: SQLiteDatabase, pragma: String): Int =
        database.rawQuery("PRAGMA $pragma", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun tableExists(database: SQLiteDatabase, tableName: String): Boolean =
        database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(tableName)
        ).use { it.moveToFirst() }
}
