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
import com.aistudio.dieselstationsms.kxmpzq.startup.DatabaseInitializationCoordinator
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DatabaseLifecycleTestApplication::class)
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

    @Test
    fun auditLogsAreAppendOnly() {
        val db = helper.writableDatabase
        val uuid = UUID.randomUUID().toString()
        db.execSQL(
            "INSERT INTO audit_logs(uuid, action_type, table_name, record_id) VALUES(?,?,?,?)",
            arrayOf(uuid, "CREATE", "inventory_movements", 1)
        )
        assertTrue(runCatching {
            db.execSQL("UPDATE audit_logs SET action_type='UPDATE' WHERE uuid=?", arrayOf(uuid))
        }.isFailure)
        assertTrue(runCatching {
            db.execSQL("DELETE FROM audit_logs WHERE uuid=?", arrayOf(uuid))
        }.isFailure)
        assertEquals("CREATE", db.rawQuery(
            "SELECT action_type FROM audit_logs WHERE uuid=?", arrayOf(uuid)
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) })
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance(context)
        DatabaseHelper.closeInstance(ApplicationProvider.getApplicationContext<Context>())
        helper.close()
        testRoot.deleteRecursively()
    }

    @Test
    fun singletonRetirementWaitsForAnInFlightDatabaseLifecycleOperation() {
        val enteredLifecycle = CountDownLatch(1)
        val releaseLifecycle = CountDownLatch(1)
        val closeCompleted = CountDownLatch(1)

        val openingThread = thread(name = "sqlite-lifecycle-open-test") {
            DatabaseInitializationCoordinator.withLifecycleLock {
                enteredLifecycle.countDown()
                check(releaseLifecycle.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(enteredLifecycle.await(5, TimeUnit.SECONDS))

        val closingThread = thread(name = "sqlite-lifecycle-close-test") {
            DatabaseHelper.closeInstance()
            closeCompleted.countDown()
        }

        val closeWasBlocked = !closeCompleted.await(100, TimeUnit.MILLISECONDS)
        releaseLifecycle.countDown()

        assertTrue("closeInstance must wait until the in-flight SQLite lifecycle section exits", closeWasBlocked)
        assertTrue("closeInstance did not complete after the lifecycle section exited",
            closeCompleted.await(5, TimeUnit.SECONDS))
        openingThread.join(5_000)
        closingThread.join(5_000)
        assertFalse("the lifecycle test threads must terminate", openingThread.isAlive || closingThread.isAlive)
    }

    @Test
    fun singletonUsesDatabasePathIdentityAndRebindsOnlyForADifferentStore() {
        val firstContext = ApplicationProvider.getApplicationContext<Context>()
        val firstHelper = DatabaseHelper.getInstance(firstContext)

        // Another Context wrapper over the same file must not retire a pool that
        // can still be used by workers holding the existing helper.
        val sameStoreContext = object : ContextWrapper(firstContext) {
            override fun getApplicationContext(): Context = this
        }
        val firstDatabase = firstHelper.readableDatabase
        val firstCursor = firstDatabase.rawQuery("SELECT COUNT(*) FROM sqlite_master", null)
        try {
            assertSame(firstHelper, DatabaseHelper.getInstance(sameStoreContext))
            assertTrue(firstDatabase.isOpen)
            assertTrue(firstCursor.moveToFirst())

            // The setUp fixture resolves to a genuinely different database file. It must
            // receive a separate helper without retiring the first store.
            val differentStoreHelper = DatabaseHelper.getInstance(context)
            assertNotSame(firstHelper, differentStoreHelper)
            assertTrue("opening a second path must not close the first store", firstDatabase.isOpen)
            assertTrue("the first store's cursor must remain usable after opening another path",
                firstCursor.moveToFirst())

            DatabaseHelper.closeInstance(context)
            assertTrue("closing the second path must leave the first store open", firstDatabase.isOpen)
            assertSame(firstHelper, DatabaseHelper.getInstance(firstContext))

            val reopenedAfterClose = DatabaseHelper.getInstance(context)
            assertNotSame("context-scoped close must retire only the requested path",
                differentStoreHelper, reopenedAfterClose)
            assertTrue(reopenedAfterClose.readableDatabase.isOpen)
        } finally {
            firstCursor.close()
            DatabaseHelper.closeInstance(context)
            DatabaseHelper.closeInstance(firstContext)
        }
    }

    @Test
    fun directHelperCloseEvictsTheCachedInstanceBeforeTheNextLookup() {
        val applicationContext = ApplicationProvider.getApplicationContext<Context>()
        val retired = DatabaseHelper.getInstance(applicationContext)
        val retiredDatabase = retired.writableDatabase
        assertTrue(retiredDatabase.isOpen)

        retired.close()

        assertFalse("direct close must retire the original SQLite connection pool", retiredDatabase.isOpen)
        val reopened = DatabaseHelper.getInstance(applicationContext)
        assertNotSame("getInstance must never return a helper that was directly closed", retired, reopened)
        assertTrue("the replacement helper must open a live SQLite database", reopened.writableDatabase.isOpen)
        assertEquals(
            "ok",
            reopened.readableDatabase.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0).lowercase()
            }
        )
        assertFalse(
            "the reopened database must not contain foreign-key violations",
            reopened.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() }
        )
    }

    @Test
    fun samePathContextLookupDoesNotRetireAnOpenCursor() {
        val firstContext = ApplicationProvider.getApplicationContext<Context>()
        val helper = DatabaseHelper.getInstance(firstContext)
        val database = helper.readableDatabase
        val cursor = database.rawQuery("SELECT COUNT(*) FROM sqlite_master", null)
        try {
            val sameStoreContext = object : ContextWrapper(firstContext) {
                override fun getApplicationContext(): Context = this
            }
            assertSame("same physical database path must preserve the process helper",
                helper, DatabaseHelper.getInstance(sameStoreContext))
            assertTrue("same-path helper lookup must keep the original connection pool open",
                database.isOpen)
            assertTrue("a cursor opened before equivalent-context lookup must remain usable",
                cursor.moveToFirst())
        } finally {
            cursor.close()
            DatabaseHelper.closeInstance(firstContext)
        }
    }

    @Test
    fun closingAndRealDatabasePathRebindingInvalidateTheStartupDatabaseCache() = runBlocking {
        val applicationContext = ApplicationProvider.getApplicationContext<Context>()
        val first = DatabaseHelper.getInstance(applicationContext)
        assertSame(first, DatabaseInitializationCoordinator.awaitDatabase(applicationContext))

        DatabaseHelper.closeInstance(applicationContext)
        val reopened = DatabaseInitializationCoordinator.awaitDatabase(applicationContext)
        assertNotSame("closing SQLite must not leave a stale cached coordinator result", first, reopened)
        assertTrue(reopened.writableDatabase.isOpen)

        val samePathContext = object : ContextWrapper(applicationContext) {
            override fun getApplicationContext(): Context = this
        }
        assertSame(reopened, DatabaseHelper.getInstance(samePathContext))
        assertSame(
            "the startup cache must remain valid for another Context wrapper over the same file",
            reopened,
            DatabaseInitializationCoordinator.awaitDatabase(samePathContext)
        )

        // The setUp fixture has another on-disk path, so this is a true rebind.
        val rebound = DatabaseHelper.getInstance(context)
        assertNotSame(reopened, rebound)
        assertSame(
            "a real database-path change must invalidate the old coordinator helper",
            rebound,
            DatabaseInitializationCoordinator.awaitDatabase(context)
        )
        assertTrue(rebound.writableDatabase.isOpen)
        assertSame(
            "returning to the first path must preserve its own coordinator result",
            reopened,
            DatabaseInitializationCoordinator.awaitDatabase(applicationContext)
        )
        DatabaseHelper.closeInstance(context)
        DatabaseHelper.closeInstance(applicationContext)
    }

    @Test
    fun closeInstanceWaitsForAnInFlightSqliteTransactionAndPreservesItsCommit() {
        val applicationContext = ApplicationProvider.getApplicationContext<Context>()
        val managedHelper = DatabaseHelper.getInstance(applicationContext)
        val database = managedHelper.writableDatabase
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS app_lifecycle_close_barrier (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, token TEXT NOT NULL UNIQUE)"
        )
        val token = "close-barrier-${UUID.randomUUID()}"
        val lockField = DatabaseHelper::class.java.getDeclaredField("dbLock").apply {
            isAccessible = true
        }
        val databaseLock = lockField.get(managedHelper) as ReentrantLock
        val operationEntered = CountDownLatch(1)
        val releaseOperation = CountDownLatch(1)
        val operationCompleted = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeCompleted = CountDownLatch(1)
        val operationFailure = AtomicReference<Throwable?>(null)
        var closeThread: Thread? = null

        val operationThread = thread(name = "sqlite-active-transaction-test") {
            try {
                databaseLock.lock()
                try {
                    database.beginTransaction()
                    try {
                        database.execSQL(
                            "INSERT INTO app_lifecycle_close_barrier(token) VALUES (?)",
                            arrayOf(token)
                        )
                        operationEntered.countDown()
                        check(releaseOperation.await(5, TimeUnit.SECONDS)) {
                            "Timed out waiting to finish the active SQLite transaction"
                        }
                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                } finally {
                    databaseLock.unlock()
                }
            } catch (failure: Throwable) {
                operationFailure.set(failure)
                operationEntered.countDown()
            } finally {
                operationCompleted.countDown()
            }
        }

        try {
            assertTrue("the SQLite transaction did not enter its critical section",
                operationEntered.await(5, TimeUnit.SECONDS))
            closeThread = thread(name = "sqlite-helper-retirement-test") {
                closeStarted.countDown()
                try {
                    DatabaseHelper.closeInstance(applicationContext)
                } finally {
                    closeCompleted.countDown()
                }
            }
            assertTrue("the helper retirement thread did not start",
                closeStarted.await(5, TimeUnit.SECONDS))
            val closeWasBlocked = !closeCompleted.await(150, TimeUnit.MILLISECONDS)
            assertTrue(
                "closeInstance must not close the SQLite pool while a transaction owns dbLock",
                closeWasBlocked
            )

            releaseOperation.countDown()
            assertTrue("the in-flight SQLite transaction did not finish",
                operationCompleted.await(5, TimeUnit.SECONDS))
            assertTrue("closeInstance did not finish after the transaction released dbLock",
                closeCompleted.await(5, TimeUnit.SECONDS))
            operationFailure.get()?.let { throw AssertionError("SQLite transaction failed", it) }

            val reopened = DatabaseHelper.getInstance(applicationContext)
            val persisted = reopened.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM app_lifecycle_close_barrier WHERE token=?",
                arrayOf(token)
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
            assertEquals("the committed write must survive helper retirement and reopening", 1, persisted)
            assertEquals("ok", reopened.readableDatabase.rawQuery("PRAGMA integrity_check", null).use {
                assertTrue(it.moveToFirst())
                it.getString(0).lowercase()
            })
            assertFalse(
                "helper retirement must not leave foreign-key violations",
                reopened.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use {
                    it.moveToFirst()
                }
            )
            reopened.writableDatabase.delete(
                "app_lifecycle_close_barrier", "token=?", arrayOf(token)
            )
        } finally {
            releaseOperation.countDown()
            operationThread.join(5_000)
            closeThread?.join(5_000)
            DatabaseHelper.closeInstance(applicationContext)
        }
        assertFalse("the SQLite transaction thread must terminate", operationThread.isAlive)
        assertFalse("the helper retirement thread must terminate", closeThread?.isAlive == true)
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


    @Test
    fun v65DatabaseMissingStockHealthTablesGetsAdditiveV67Repair() {
        val database = helper.writableDatabase
        database.execSQL(
            "CREATE TABLE v65_stock_schema_preservation (id INTEGER PRIMARY KEY, marker TEXT NOT NULL)"
        )
        database.execSQL(
            "INSERT INTO v65_stock_schema_preservation(id, marker) VALUES (1, 'keep-existing-data')"
        )

        // Model a V65 installation created before the Phase 1–4 ensure functions
        // were added without advancing SQLiteOpenHelper's version.
        database.execSQL("DROP TABLE IF EXISTS inventory_anomalies")
        database.execSQL("DROP TABLE IF EXISTS product_batches")
        database.execSQL("DROP TABLE IF EXISTS tank_readings")
        database.version = 65
        helper.close()

        val upgraded = helper.writableDatabase
        assertEquals(DatabaseHelper.VERSION, upgraded.version)
        listOf("inventory_anomalies", "product_batches", "tank_readings").forEach { table ->
            assertTrue("V65 -> V67 must create $table", tableExists(upgraded, table))
        }
        val preserved = upgraded.rawQuery(
            "SELECT marker FROM v65_stock_schema_preservation WHERE id = 1", null
        ).use { cursor ->
            assertTrue("pre-existing V65 row must survive the upgrade", cursor.moveToFirst())
            cursor.getString(0)
        }
        assertEquals("keep-existing-data", preserved)

        val anomalyColumns = tableColumns(upgraded, "inventory_anomalies")
        assertTrue("anomaly_id is required by stock-level anomaly queries", "anomaly_id" in anomalyColumns)
        assertTrue("station_id is required by station-scoped anomaly queries", "station_id" in anomalyColumns)
        assertTrue("status is required by OPEN anomaly filtering", "status" in anomalyColumns)

        val batchColumns = tableColumns(upgraded, "product_batches")
        assertTrue("batch expiry is required by product stock-level summaries", "expiry_date" in batchColumns)
        assertTrue("batch quantity is required by product stock-level summaries", "quantity" in batchColumns)
        assertTrue("batch product linkage is required", "product_id" in batchColumns)

        val readingColumns = tableColumns(upgraded, "tank_readings")
        listOf("reading_id", "tank_id", "reading_at", "water_level_mm").forEach { column ->
            assertTrue("tank_readings.$column must exist", column in readingColumns)
        }
        assertTrue(
            "tank reading lookup index must be installed",
            upgraded.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='index' AND name='idx_phase1_tank_readings_tank_at'",
                null
            ).use { it.moveToFirst() }
        )
        assertEquals("ok", upgraded.rawQuery("PRAGMA integrity_check", null).use {
            assertTrue(it.moveToFirst()); it.getString(0).lowercase()
        })
        assertFalse("upgrade must not introduce foreign-key violations",
            upgraded.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() })

        helper.close()
        val reopened = helper.writableDatabase
        assertEquals(DatabaseHelper.VERSION, reopened.version)
        listOf("inventory_anomalies", "product_batches", "tank_readings").forEach { table ->
            assertTrue("$table must remain after closing and reopening", tableExists(reopened, table))
        }
        assertEquals("keep-existing-data", reopened.rawQuery(
            "SELECT marker FROM v65_stock_schema_preservation WHERE id = 1", null
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) })
        assertEquals("ok", reopened.rawQuery("PRAGMA integrity_check", null).use {
            assertTrue(it.moveToFirst()); it.getString(0).lowercase()
        })
        assertFalse(reopened.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() })
    }

    private fun tableColumns(database: SQLiteDatabase, table: String): Set<String> =
        database.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
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
