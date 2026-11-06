package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseHelperOpenLifecycleTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        val applicationContext = ApplicationProvider.getApplicationContext<Context>()
        testRoot = File(applicationContext.cacheDir, "sqlite-lifecycle-${UUID.randomUUID()}")
            .apply { check(mkdirs()) }

        context = object : ContextWrapper(applicationContext) {
            override fun getApplicationContext(): Context = this

            override fun getDatabasePath(name: String): File =
                File(testRoot, "databases/$name").also { it.parentFile?.mkdirs() }

            override fun getExternalFilesDir(type: String?): File =
                File(testRoot, "external").also { it.mkdirs() }

            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?
            ): SQLiteDatabase {
                var flags = SQLiteDatabase.CREATE_IF_NECESSARY
                if ((mode and Context.MODE_ENABLE_WRITE_AHEAD_LOGGING) != 0) {
                    flags = flags or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
                }
                return SQLiteDatabase.openDatabase(getDatabasePath(name).absolutePath, factory, flags)
            }

            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase {
                var flags = SQLiteDatabase.CREATE_IF_NECESSARY
                if ((mode and Context.MODE_ENABLE_WRITE_AHEAD_LOGGING) != 0) {
                    flags = flags or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
                }
                return SQLiteDatabase.openDatabase(getDatabasePath(name).absolutePath, factory, flags, errorHandler)
            }
        }

        val constructor = DatabaseHelper::class.java.getDeclaredConstructor(Context::class.java).apply {
            isAccessible = true
        }
        helper = constructor.newInstance(context)
    }

    @After
    fun tearDown() {
        helper.close()
        testRoot.deleteRecursively()
    }

    @Test
    fun singletonIsStableForOneApplicationContextAndReboundForAnother() {
        val firstContext = ApplicationProvider.getApplicationContext<Context>()
        val firstHelper = DatabaseHelper.getInstance(firstContext)

        val secondContext = object : ContextWrapper(firstContext) {
            override fun getApplicationContext(): Context = this
        }
        val secondHelper = DatabaseHelper.getInstance(secondContext)

        assertNotSame("a helper must not retain a different app context's database path", firstHelper, secondHelper)
        assertSame(secondHelper, DatabaseHelper.getInstance(secondContext))

        DatabaseHelper.closeInstance()
        assertSame(
            "closing connections must not replace the process helper for the same context",
            secondHelper,
            DatabaseHelper.getInstance(secondContext)
        )
    }

    @Test
    fun reopeningIsLightweightAndKeepsTheHelperUsable() {
        val database = helper.writableDatabase
        val schemaVersion = pragmaInt(database, "schema_version")
        assertEquals(DatabaseHelper.VERSION, database.version)

        val startedAt = System.nanoTime()
        repeat(4) {
            helper.close()
            val reopened = helper.readableDatabase
            assertTrue(reopened.isOpen)
        }
        val averageOpenMs = ((System.nanoTime() - startedAt) / 1_000_000.0) / 4.0
        val finalDatabase = helper.readableDatabase

        assertEquals("onOpen must not execute schema DDL", schemaVersion, pragmaInt(finalDatabase, "schema_version"))
        assertTrue("repeated SQLite opens were unexpectedly slow: ${averageOpenMs}ms", averageOpenMs < 1_000.0)
        assertTrue(finalDatabase.isOpen)
    }

    @Test
    fun v48CompatibilityMigrationPreservesExistingSettingsAndCreatesLegacyObjects() {
        val database = helper.writableDatabase
        val marker = "lifecycle_migration_marker"
        database.execSQL(
            """
            INSERT INTO settings
                (setting_key, setting_value, setting_type, description, is_editable, created_at, updated_at)
            VALUES (?, ?, 'string', 'upgrade regression marker', 1, '2026-11-01 00:00:00', '2026-11-01 00:00:00')
            """.trimIndent(),
            arrayOf(marker, "preserved")
        )
        database.version = 48
        helper.close()

        val upgraded = helper.writableDatabase
        assertEquals(DatabaseHelper.VERSION, upgraded.version)
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
        database.execSQL("DROP TABLE IF EXISTS notifications")
        database.execSQL(
            """
            CREATE TEMP TRIGGER force_v49_rollback
            BEFORE INSERT ON permissions
            BEGIN
                SELECT RAISE(ABORT, 'forced compatibility migration failure');
            END
            """.trimIndent()
        )
        database.version = 48

        var migrationFailed = false
        try {
            val onUpgrade = DatabaseHelper::class.java.getDeclaredMethod(
                "onUpgrade",
                SQLiteDatabase::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            ).apply { isAccessible = true }
            onUpgrade.invoke(helper, database, 48, 49)
        } catch (_: Exception) {
            migrationFailed = true
        } finally {
            // TEMP triggers are connection-local, and are removed before this test
            // releases the isolated database connection.
            database.execSQL("DROP TRIGGER IF EXISTS force_v49_rollback")
        }

        assertTrue("migration failure trigger did not abort onUpgrade", migrationFailed)
        assertEquals("user_version must roll back with the failed DDL transaction", 48, database.version)
        assertFalse(
            "DDL performed before the injected failure must also roll back",
            tableExists(database, "notifications")
        )
        assertFalse(database.inTransaction())
    }

    @Test
    fun backupKeepsTheSharedHelperUsableAndProducesAnIntegrityCheckedSnapshot() {
        val database = helper.writableDatabase
        database.rawQuery("PRAGMA wal_autocheckpoint = 0", null).use { }
        val path = helper.backupDatabase()
        val backup = File(path)
        try {
            assertTrue(backup.exists() && backup.length() > 0L)
            assertTrue("the helper must remain usable after backup", helper.readableDatabase.isOpen)
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
            helper.writableDatabase.rawQuery("PRAGMA wal_autocheckpoint = 1000", null).use { }
        }
    }

    @Test
    fun backupRestoreRoundTripRestoresTheCommittedDatabaseSnapshot() {
        val database = helper.writableDatabase
        database.execSQL("CREATE TABLE restore_round_trip_marker (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
        database.execSQL("INSERT INTO restore_round_trip_marker(id, value) VALUES (1, 'before-backup')")

        val backupPath = helper.backupDatabase()
        val backupFile = File(backupPath)
        try {
            val preview = helper.getRestorePreview(backupPath)
            assertTrue("a verified current-schema backup should be restorable", preview.optBoolean("can_restore"))

            helper.writableDatabase.execSQL(
                "UPDATE restore_round_trip_marker SET value = 'after-backup' WHERE id = 1"
            )
            assertEquals("after-backup", markerValue())

            val restored = helper.restoreDatabaseSafe(backupPath)
            assertTrue("restore must report an integrity-checked success: $restored", restored.optBoolean("success"))
            assertTrue(restored.optBoolean("integrity_verified"))
            assertEquals("before-backup", markerValue())
            assertEquals("ok", helper.readableDatabase.rawQuery("PRAGMA integrity_check", null).use {
                assertTrue(it.moveToFirst())
                it.getString(0).lowercase()
            })
            assertFalse(
                "restore metadata must not contain orphaned foreign keys",
                helper.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() }
            )
            val latestRestoreHasBackupReference = helper.readableDatabase.rawQuery(
                "SELECT status, backup_id FROM restore_history ORDER BY id DESC LIMIT 1", null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("success", cursor.getString(0))
                !cursor.isNull(1)
            }
            assertTrue("restore history must reference a backup row in the restored database", latestRestoreHasBackupReference)
        } finally {
            backupFile.delete()
            File(backupPath + "-wal").delete()
            File(backupPath + "-shm").delete()
            helper.close()
        }
    }

    private fun markerValue(): String = helper.readableDatabase.rawQuery(
        "SELECT value FROM restore_round_trip_marker WHERE id = 1", null
    ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getString(0)
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
