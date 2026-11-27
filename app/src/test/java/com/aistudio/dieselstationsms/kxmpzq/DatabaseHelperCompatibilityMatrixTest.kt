package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class DatabaseHelperCompatibilityMatrixTest {
    @Test
    @Config(sdk = [26, 28, 30, 33, 34, 35])
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

    @Test
    @Config(sdk = [26])
    fun upgradesV62AndV63FleetSchemaWithoutLosingLegacyLocationRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(62, 63).forEach { legacyVersion ->
            DatabaseHelper.closeInstance()
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
            try {
                val before = DatabaseHelper.getInstance(context).writableDatabase
                // Fresh databases intentionally contain no operational customers.
                // Create a minimal FK-valid owner instead of depending on user data.
                val partyTypeId = before.rawQuery(
                    "SELECT id FROM party_types WHERE COALESCE(is_deleted, 0)=0 ORDER BY id LIMIT 1", null
                ).use { cursor ->
                    assertTrue("fixture requires the built-in party type reference row", cursor.moveToFirst())
                    cursor.getLong(0)
                }
                val partyId = before.insertOrThrow("parties", null, android.content.ContentValues().apply {
                    put("uuid", "V64-MIGRATION-PARTY-$legacyVersion")
                    put("party_code", "V64-PARTY-$legacyVersion")
                    put("party_type_id", partyTypeId)
                    put("commercial_name", "Fleet migration fixture $legacyVersion")
                    put("is_active", 1)
                    put("is_deleted", 0)
                })
                val vehicleId = before.insertOrThrow("vehicles", null, android.content.ContentValues().apply {
                    put("uuid", "V64-MIGRATION-VEHICLE-$legacyVersion")
                    put("vehicle_code", "V64-$legacyVersion")
                    put("party_id", partyId)
                    put("plate_number", "MIG-$legacyVersion")
                    put("status", "active")
                    put("is_deleted", 0)
                })
                val locationId = before.insertOrThrow("vehicle_locations", null, android.content.ContentValues().apply {
                    put("uuid", "V64-MIGRATION-LOCATION-$legacyVersion")
                    put("vehicle_id", vehicleId)
                    put("latitude", 15.3694)
                    put("longitude", 44.1910)
                    put("speed", 27.5)
                    put("location_time", "2026-11-16 08:15:00")
                })
                assertTrue(locationId > 0L)
                before.beginTransaction()
                try {
                    before.execSQL("""
                        CREATE TABLE vehicle_locations_v64_legacy AS
                        SELECT id, uuid, vehicle_id, latitude, longitude, speed, heading,
                               fuel_level, odometer, altitude, accuracy, location_time, created_at
                        FROM vehicle_locations
                    """.trimIndent())
                    before.execSQL("DROP TABLE vehicle_locations")
                    before.execSQL("ALTER TABLE vehicle_locations_v64_legacy RENAME TO vehicle_locations")
                    before.version = legacyVersion
                    before.setTransactionSuccessful()
                } finally {
                    before.endTransaction()
                }
                DatabaseHelper.closeInstance()
                val upgraded = DatabaseHelper.getInstance(context).writableDatabase
                assertEquals("legacy V$legacyVersion must reach the current database schema", DatabaseHelper.VERSION, upgraded.version)
                val columns = upgraded.rawQuery("PRAGMA table_info(vehicle_locations)", null).use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
                }
                assertTrue("V64 must add vehicle_locations.trip_id", "trip_id" in columns)
                upgraded.rawQuery(
                    "SELECT latitude, longitude, speed FROM vehicle_locations WHERE id=?",
                    arrayOf(locationId.toString())
                ).use { cursor ->
                    assertTrue("legacy location row must survive V$legacyVersion -> V64", cursor.moveToFirst())
                    assertEquals(15.3694, cursor.getDouble(0), 0.000001)
                    assertEquals(44.1910, cursor.getDouble(1), 0.000001)
                    assertEquals(27.5, cursor.getDouble(2), 0.000001)
                }
                val fleetIndex = upgraded.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='index' AND name='idx_vehicle_locations_trip_time'",
                    null
                ).use { it.moveToFirst() }
                assertTrue("fleet trip/time index must be restored", fleetIndex)
                val integrity = upgraded.rawQuery("PRAGMA integrity_check", null).use {
                    assertTrue(it.moveToFirst()); it.getString(0)
                }
                assertEquals("ok", integrity)
                val fkViolations = upgraded.rawQuery("PRAGMA foreign_key_check", null).use { it.count }
                assertEquals("upgrade must not introduce foreign-key violations", 0, fkViolations)
            } finally {
                DatabaseHelper.closeInstance()
                context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
            }
        }
    }

    @Test
    @Config(sdk = [26])
    fun upgradesV43BusinessDaysInYemenAndPreservesFinancialHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previousTimeZone = TimeZone.getDefault()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val before = DatabaseHelper.getInstance(context).writableDatabase
            val userId = before.insertOrThrow("users", null, ContentValues().apply {
                put("uuid", "V44-TZ-USER")
                put("username", "v44_tz_user")
                put("password_hash", "test-only")
                put("full_name", "V44 timezone fixture")
                put("role_id", 1)
                put("station_id", 1)
                put("is_deleted", 0)
            })
            val shiftId = before.insertOrThrow("shifts", null, ContentValues().apply {
                put("uuid", "V44-TZ-SHIFT")
                put("shift_code", "V44-TZ-SHIFT")
                put("station_id", 1)
                put("shift_date", "2026-11-16")
                put("shift_type", "night")
                put("start_time", "2026-11-16 20:00:00")
                put("cashier_id", userId)
            })
            val saleId = before.insertOrThrow("sales_transactions", null, ContentValues().apply {
                put("uuid", "V44-TZ-SALE")
                put("sale_code", "V44-TZ-SALE")
                put("invoice_number", "V44-TZ-INVOICE")
                put("station_id", 1)
                put("shift_id", shiftId)
                put("cashier_id", userId)
                put("subtotal", 100.0)
                put("gross_amount", 100.0)
                put("net_amount", 100.0)
                put("paid_amount", 25.0)
                put("remaining_amount", 75.0)
                put("created_at", "2026-11-16T21:30:00Z")
                put("business_day", "2026-11-16")
            })
            val ambiguousLocalSaleId = before.insertOrThrow("sales_transactions", null, ContentValues().apply {
                put("uuid", "V44-TZ-SALE-LOCAL")
                put("sale_code", "V44-TZ-SALE-LOCAL")
                put("invoice_number", "V44-TZ-INVOICE-LOCAL")
                put("station_id", 1)
                put("shift_id", shiftId)
                put("cashier_id", userId)
                put("subtotal", 50.0)
                put("gross_amount", 50.0)
                put("net_amount", 50.0)
                put("paid_amount", 50.0)
                put("remaining_amount", 0.0)
                put("created_at", "2026-11-16 23:45:00")
                put("business_day", "2026-11-16")
            })
            val paymentId = before.insertOrThrow("payments", null, ContentValues().apply {
                put("uuid", "V44-TZ-PAYMENT")
                put("payment_code", "V44-TZ-PAYMENT")
                put("station_id", 1)
                put("sale_id", saleId)
                put("payment_type", "cash")
                put("payment_method", "cash")
                put("amount", 25.0)
                put("created_at", "2026-11-16T21:45:00Z")
                put("business_day", "2026-11-16")
            })
            val fuelSaleId = before.insertOrThrow("fuel_sales", null, ContentValues().apply {
                put("uuid", "V44-TZ-FUEL")
                put("sale_id", saleId)
                put("station_id", 1)
                put("quantity", 10.0)
                put("price_per_liter", 10.0)
                put("total_amount", 100.0)
                put("created_at", "2026-11-16T22:00:00Z")
                put("business_day", "2026-11-16")
            })
            val fuelSaleWithExplicitDateId = before.insertOrThrow("fuel_sales", null, ContentValues().apply {
                put("uuid", "V44-TZ-FUEL-EXPLICIT")
                put("sale_id", saleId)
                put("station_id", 1)
                put("quantity", 2.0)
                put("price_per_liter", 10.0)
                put("total_amount", 20.0)
                put("sale_date", "2026-11-16")
                put("created_at", "2026-11-16 22:00:00")
                put("business_day", "2026-11-16")
            })
            val stockMovementId = before.insertOrThrow("stock_movements", null, ContentValues().apply {
                put("uuid", "V44-TZ-STOCK")
                put("movement_type", "in")
                put("quantity", 3.0)
                put("created_at", "2026-11-16T22:15:00Z")
                put("business_day", "2026-11-16")
            })
            val stockMovementWithExplicitDateId = before.insertOrThrow("stock_movements", null, ContentValues().apply {
                put("uuid", "V44-TZ-STOCK-EXPLICIT")
                put("movement_type", "in")
                put("quantity", 4.0)
                put("movement_date", "2026-11-16")
                put("created_at", "2026-11-16 22:15:00")
                put("business_day", "2026-11-16")
            })
            val countsBefore = listOf("sales_transactions", "payments", "fuel_sales", "stock_movements").associateWith { table ->
                before.rawQuery("SELECT COUNT(*) FROM $table", null).use { assertTrue(it.moveToFirst()); it.getInt(0) }
            }
            before.version = 43
            DatabaseHelper.closeInstance()

            val upgraded = DatabaseHelper.getInstance(context).writableDatabase
            assertEquals("V43 must upgrade through all migrations to the current schema", DatabaseHelper.VERSION, upgraded.version)

            fun businessDay(table: String, id: Long): String =
                upgraded.rawQuery("SELECT business_day FROM $table WHERE id=?", arrayOf(id.toString())).use {
                    assertTrue("$table row $id must survive the upgrade", it.moveToFirst())
                    it.getString(0)
                }

            assertEquals("2026-11-17", businessDay("sales_transactions", saleId))
            assertEquals("2026-11-16", businessDay("sales_transactions", ambiguousLocalSaleId))
            upgraded.rawQuery(
                "SELECT created_at FROM sales_transactions WHERE id=?",
                arrayOf(ambiguousLocalSaleId.toString())
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("2026-11-16 23:45:00", it.getString(0))
            }
            assertEquals("2026-11-17", businessDay("payments", paymentId))
            assertEquals("2026-11-17", businessDay("fuel_sales", fuelSaleId))
            assertEquals("2026-11-16", businessDay("fuel_sales", fuelSaleWithExplicitDateId))
            assertEquals("2026-11-17", businessDay("stock_movements", stockMovementId))
            assertEquals("2026-11-16", businessDay("stock_movements", stockMovementWithExplicitDateId))

            upgraded.rawQuery(
                "SELECT created_at, net_amount, paid_amount, remaining_amount FROM sales_transactions WHERE id=?",
                arrayOf(saleId.toString())
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("2026-11-16T21:30:00Z", it.getString(0))
                assertEquals(100.0, it.getDouble(1), 0.000001)
                assertEquals(25.0, it.getDouble(2), 0.000001)
                assertEquals(75.0, it.getDouble(3), 0.000001)
            }
            upgraded.rawQuery(
                "SELECT created_at, amount, sale_id FROM payments WHERE id=?",
                arrayOf(paymentId.toString())
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("2026-11-16T21:45:00Z", it.getString(0))
                assertEquals(25.0, it.getDouble(1), 0.000001)
                assertEquals(saleId, it.getLong(2))
            }
            countsBefore.forEach { (table, count) ->
                val countAfter = upgraded.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                    assertTrue(it.moveToFirst()); it.getInt(0)
                }
                assertEquals("$table row count must be preserved by V43 migration", count, countAfter)
            }
            assertEquals("ok", upgraded.rawQuery("PRAGMA integrity_check", null).use {
                assertTrue(it.moveToFirst()); it.getString(0)
            })
            assertEquals(0, upgraded.rawQuery("PRAGMA foreign_key_check", null).use { it.count })
        } finally {
            DatabaseHelper.closeInstance()
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
            TimeZone.setDefault(previousTimeZone)
        }
    }

    @Test
    @Config(sdk = [26])
    fun upgradesAlreadyInstalledV64DatabaseWithLegacyUtcPaymentDay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previousTimeZone = TimeZone.getDefault()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val before = DatabaseHelper.getInstance(context).writableDatabase
            val paymentId = before.insertOrThrow("payments", null, ContentValues().apply {
                put("uuid", "V65-EXISTING-PAYMENT")
                put("payment_code", "V65-EXISTING-PAYMENT")
                put("payment_type", "cash")
                put("payment_method", "cash")
                put("amount", 42.5)
                put("created_at", "2026-11-16T21:45:00Z")
                put("business_day", "2026-11-16")
            })
            val localWallClockPaymentId = before.insertOrThrow("payments", null, ContentValues().apply {
                put("uuid", "V65-LOCAL-WALL-CLOCK-PAYMENT")
                put("payment_code", "V65-LOCAL-WALL-CLOCK-PAYMENT")
                put("payment_type", "cash")
                put("payment_method", "cash")
                put("amount", 17.25)
                put("created_at", "2026-11-16 21:45:00")
                put("business_day", "2026-11-16")
            })
            val offsetPaymentId = before.insertOrThrow("payments", null, ContentValues().apply {
                put("uuid", "V65-OFFSET-PAYMENT")
                put("payment_code", "V65-OFFSET-PAYMENT")
                put("payment_type", "cash")
                put("payment_method", "cash")
                put("amount", 8.75)
                put("created_at", "2026-11-16T23:30:00.125-02:00")
                put("business_day", "2026-11-16")
            })
            val invalidTimestampPaymentId = before.insertOrThrow("payments", null, ContentValues().apply {
                put("uuid", "V65-INVALID-TIMESTAMP-PAYMENT")
                put("payment_code", "V65-INVALID-TIMESTAMP-PAYMENT")
                put("payment_type", "cash")
                put("payment_method", "cash")
                put("amount", 5.5)
                put("created_at", "2026-11-16T25:45:00Z")
                put("business_day", "2026-11-16")
            })
            val userId = before.insertOrThrow("users", null, ContentValues().apply {
                put("uuid", "V65-TZ-USER")
                put("username", "v65_tz_user")
                put("password_hash", "test-only")
                put("full_name", "V65 timezone fixture")
                put("role_id", 1)
                put("station_id", 1)
                put("is_deleted", 0)
            })
            val shiftId = before.insertOrThrow("shifts", null, ContentValues().apply {
                put("uuid", "V65-TZ-SHIFT")
                put("shift_code", "V65-TZ-SHIFT")
                put("station_id", 1)
                put("shift_date", "2026-11-16")
                put("shift_type", "night")
                put("start_time", "2026-11-16 20:00:00")
                put("cashier_id", userId)
            })
            val saleId = before.insertOrThrow("sales_transactions", null, ContentValues().apply {
                put("uuid", "V65-TZ-SALE")
                put("sale_code", "V65-TZ-SALE")
                put("invoice_number", "V65-TZ-INVOICE")
                put("station_id", 1)
                put("shift_id", shiftId)
                put("cashier_id", userId)
                put("subtotal", 100.0)
                put("gross_amount", 100.0)
                put("net_amount", 100.0)
                put("paid_amount", 30.0)
                put("remaining_amount", 70.0)
                put("created_at", "2026-11-16T21:30:00Z")
                put("business_day", "2026-11-16")
            })
            val fuelSaleId = before.insertOrThrow("fuel_sales", null, ContentValues().apply {
                put("uuid", "V65-TZ-FUEL")
                put("sale_id", saleId)
                put("station_id", 1)
                put("quantity", 10.0)
                put("price_per_liter", 10.0)
                put("total_amount", 100.0)
                put("created_at", "2026-11-16T22:00:00.500Z")
                put("business_day", "2026-11-16")
            })
            val fuelSaleWithExplicitDateId = before.insertOrThrow("fuel_sales", null, ContentValues().apply {
                put("uuid", "V65-TZ-FUEL-EXPLICIT")
                put("sale_id", saleId)
                put("station_id", 1)
                put("quantity", 2.0)
                put("price_per_liter", 10.0)
                put("total_amount", 20.0)
                put("sale_date", "2026-11-16")
                put("created_at", "2026-11-16T22:00:00Z")
                put("business_day", "2026-11-16")
            })
            val stockMovementId = before.insertOrThrow("stock_movements", null, ContentValues().apply {
                put("uuid", "V65-TZ-STOCK")
                put("movement_type", "in")
                put("quantity", 3.0)
                put("created_at", "2026-11-16T22:15:00+00:00")
                put("business_day", "2026-11-16")
            })
            val stockMovementWithExplicitDateId = before.insertOrThrow("stock_movements", null, ContentValues().apply {
                put("uuid", "V65-TZ-STOCK-EXPLICIT")
                put("movement_type", "in")
                put("quantity", 4.0)
                put("movement_date", "2026-11-16")
                put("created_at", "2026-11-16T22:15:00Z")
                put("business_day", "2026-11-16")
            })
            val missingDayUtcPaymentId = before.insertOrThrow("payments", null, ContentValues().apply {
                put("uuid", "V65-MISSING-DAY-UTC")
                put("payment_code", "V65-MISSING-DAY-UTC")
                put("payment_type", "cash")
                put("payment_method", "cash")
                put("amount", 11.0)
                put("created_at", "2026-11-16T21:45:00Z")
            })
            val blankDayWallClockPaymentId = before.insertOrThrow("payments", null, ContentValues().apply {
                put("uuid", "V65-BLANK-DAY-WALL-CLOCK")
                put("payment_code", "V65-BLANK-DAY-WALL-CLOCK")
                put("payment_type", "cash")
                put("payment_method", "cash")
                put("amount", 12.0)
                put("created_at", "2026-11-16 21:45:00")
                put("business_day", "")
            })
            val invalidUndatedPaymentId = before.insertOrThrow("payments", null, ContentValues().apply {
                put("uuid", "V65-INVALID-UNDATED")
                put("payment_code", "V65-INVALID-UNDATED")
                put("payment_type", "cash")
                put("payment_method", "cash")
                put("amount", 13.0)
                put("created_at", "2026-11-16T25:45:00Z")
            })
            val noTimestampPaymentId = before.insertOrThrow("payments", null, ContentValues().apply {
                put("uuid", "V65-NO-TIMESTAMP")
                put("payment_code", "V65-NO-TIMESTAMP")
                put("payment_type", "cash")
                put("payment_method", "cash")
                put("amount", 14.0)
                putNull("created_at")
            })
            val missingDayFuelSaleId = before.insertOrThrow("fuel_sales", null, ContentValues().apply {
                put("uuid", "V65-MISSING-DAY-FUEL")
                put("sale_id", saleId)
                put("station_id", 1)
                put("quantity", 1.0)
                put("price_per_liter", 10.0)
                put("total_amount", 10.0)
                put("sale_date", "2026-11-15")
                put("created_at", "2026-11-16T22:00:00Z")
            })
            val missingDayStockMovementId = before.insertOrThrow("stock_movements", null, ContentValues().apply {
                put("uuid", "V65-MISSING-DAY-STOCK")
                put("movement_type", "in")
                put("quantity", 2.0)
                put("movement_date", "2026-11-14")
                put("created_at", "2026-11-16T22:15:00Z")
            })
            val preservedCounts = listOf("sales_transactions", "payments", "fuel_sales", "stock_movements").associateWith { table ->
                before.rawQuery("SELECT COUNT(*) FROM $table", null).use { assertTrue(it.moveToFirst()); it.getInt(0) }
            }
            before.version = 64
            DatabaseHelper.closeInstance()

            val upgraded = DatabaseHelper.getInstance(context).writableDatabase
            assertEquals(DatabaseHelper.VERSION, upgraded.version)
            upgraded.rawQuery(
                "SELECT business_day, created_at, amount FROM payments WHERE id=?",
                arrayOf(localWallClockPaymentId.toString())
            ).use {
                assertTrue("zone-less local payment must survive V64 -> V65", it.moveToFirst())
                assertEquals("2026-11-16", it.getString(0))
                assertEquals("2026-11-16 21:45:00", it.getString(1))
                assertEquals(17.25, it.getDouble(2), 0.000001)
            }
            upgraded.rawQuery(
                "SELECT business_day, created_at, amount FROM payments WHERE id=?",
                arrayOf(paymentId.toString())
            ).use {
                assertTrue("legacy payment must survive V64 -> V65", it.moveToFirst())
                assertEquals("2026-11-17", it.getString(0))
                assertEquals("2026-11-16T21:45:00Z", it.getString(1))
                assertEquals(42.5, it.getDouble(2), 0.000001)
            }
            upgraded.rawQuery("SELECT business_day, created_at, amount FROM payments WHERE id=?", arrayOf(offsetPaymentId.toString())).use {
                assertTrue(it.moveToFirst())
                assertEquals("2026-11-17", it.getString(0))
                assertEquals("2026-11-16T23:30:00.125-02:00", it.getString(1))
                assertEquals(8.75, it.getDouble(2), 0.000001)
            }
            upgraded.rawQuery("SELECT business_day, created_at, amount FROM payments WHERE id=?", arrayOf(invalidTimestampPaymentId.toString())).use {
                assertTrue(it.moveToFirst())
                assertEquals("2026-11-16", it.getString(0))
                assertEquals("2026-11-16T25:45:00Z", it.getString(1))
                assertEquals(5.5, it.getDouble(2), 0.000001)
            }
            upgraded.rawQuery("SELECT business_day, created_at, net_amount, paid_amount, remaining_amount FROM sales_transactions WHERE id=?", arrayOf(saleId.toString())).use {
                assertTrue(it.moveToFirst())
                assertEquals("2026-11-17", it.getString(0))
                assertEquals("2026-11-16T21:30:00Z", it.getString(1))
                assertEquals(100.0, it.getDouble(2), 0.000001)
                assertEquals(30.0, it.getDouble(3), 0.000001)
                assertEquals(70.0, it.getDouble(4), 0.000001)
            }
            upgraded.rawQuery("SELECT business_day, created_at, quantity, total_amount FROM fuel_sales WHERE id=?", arrayOf(fuelSaleId.toString())).use {
                assertTrue(it.moveToFirst())
                assertEquals("2026-11-17", it.getString(0))
                assertEquals("2026-11-16T22:00:00.500Z", it.getString(1))
                assertEquals(10.0, it.getDouble(2), 0.000001)
                assertEquals(100.0, it.getDouble(3), 0.000001)
            }
            upgraded.rawQuery("SELECT business_day, sale_date FROM fuel_sales WHERE id=?", arrayOf(fuelSaleWithExplicitDateId.toString())).use {
                assertTrue(it.moveToFirst())
                assertEquals("2026-11-16", it.getString(0))
                assertEquals("2026-11-16", it.getString(1))
            }
            upgraded.rawQuery("SELECT business_day, created_at, quantity FROM stock_movements WHERE id=?", arrayOf(stockMovementId.toString())).use {
                assertTrue(it.moveToFirst())
                assertEquals("2026-11-17", it.getString(0))
                assertEquals("2026-11-16T22:15:00+00:00", it.getString(1))
                assertEquals(3.0, it.getDouble(2), 0.000001)
            }
            upgraded.rawQuery("SELECT business_day, movement_date FROM stock_movements WHERE id=?", arrayOf(stockMovementWithExplicitDateId.toString())).use {
                assertTrue(it.moveToFirst())
                assertEquals("2026-11-16", it.getString(0))
                assertEquals("2026-11-16", it.getString(1))
            }
            upgraded.rawQuery("SELECT business_day FROM payments WHERE id=?", arrayOf(missingDayUtcPaymentId.toString())).use {
                assertTrue(it.moveToFirst())
                assertEquals("2026-11-17", it.getString(0))
            }
            upgraded.rawQuery("SELECT business_day FROM payments WHERE id=?", arrayOf(blankDayWallClockPaymentId.toString())).use {
                assertTrue(it.moveToFirst())
                assertEquals("2026-11-16", it.getString(0))
            }
            upgraded.rawQuery("SELECT business_day FROM payments WHERE id=?", arrayOf(invalidUndatedPaymentId.toString())).use {
                assertTrue(it.moveToFirst())
                assertTrue("invalid explicit-offset timestamps must remain undated", it.isNull(0))
            }
            upgraded.rawQuery("SELECT business_day, created_at FROM payments WHERE id=?", arrayOf(noTimestampPaymentId.toString())).use {
                assertTrue(it.moveToFirst())
                assertTrue("missing timestamps must not be replaced with the device/current date", it.isNull(0))
                assertTrue(it.isNull(1))
            }
            upgraded.rawQuery("SELECT business_day, sale_date FROM fuel_sales WHERE id=?", arrayOf(missingDayFuelSaleId.toString())).use {
                assertTrue(it.moveToFirst())
                assertEquals("2026-11-15", it.getString(0))
                assertEquals("2026-11-15", it.getString(1))
            }
            upgraded.rawQuery("SELECT business_day, movement_date FROM stock_movements WHERE id=?", arrayOf(missingDayStockMovementId.toString())).use {
                assertTrue(it.moveToFirst())
                assertEquals("2026-11-14", it.getString(0))
                assertEquals("2026-11-14", it.getString(1))
            }
            preservedCounts.forEach { (table, count) ->
                val countAfter = upgraded.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                    assertTrue(it.moveToFirst()); it.getInt(0)
                }
                assertEquals("$table rows must not be lost during V64 -> V65", count, countAfter)
            }
            assertEquals("ok", upgraded.rawQuery("PRAGMA integrity_check", null).use {
                assertTrue(it.moveToFirst()); it.getString(0)
            })
            assertEquals(0, upgraded.rawQuery("PRAGMA foreign_key_check", null).use { it.count })
        } finally {
            DatabaseHelper.closeInstance()
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
            TimeZone.setDefault(previousTimeZone)
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
