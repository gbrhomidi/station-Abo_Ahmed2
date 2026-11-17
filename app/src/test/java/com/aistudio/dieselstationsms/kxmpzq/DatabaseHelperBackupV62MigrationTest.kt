package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Exercises the real Android SQLiteOpenHelper upgrade path with exact V62 DDL for
 * the 12 fleet/accounting tables in scope, extracted from the user's backup. The
 * remaining empty reference schema is created by the production helper. Only DDL
 * is stored in test resources; every operational row is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseHelperBackupV62MigrationTest {
    @Test
    @Config(sdk = [26])
    fun upgradesBackupDerivedV62MigrationSurfaceThroughAndroidSqliteOpenHelper() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        try {
            val databaseFile = createSanitizedV62Database(context)
            val legacy = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                assertEquals(62, legacy.version)
                assertEquals("all 12 migration-surface tables must use the backup-derived V62 schema", 12,
                    scalarInt(legacy, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN " +
                        "('parties','vehicles','drivers','vehicle_trips','vehicle_locations','vehicle_expenses'," +
                        "'vehicle_trip_events','deliveries','sales_transactions','payments','fuel_sales','stock_movements')"))
                assertFalse("the V62 backup schema must not already contain vehicle_locations.trip_id",
                    tableColumns(legacy, "vehicle_locations").contains("trip_id"))
                assertEquals(3, scalarInt(legacy, "SELECT COUNT(*) FROM vehicle_locations"))
                assertEquals(3, scalarInt(legacy, "SELECT COUNT(*) FROM vehicle_trips"))
            } finally {
                legacy.close()
            }

            // This opens the prebuilt V62 file through the production SQLiteOpenHelper.
            val upgraded = DatabaseHelper.getInstance(context).writableDatabase
            assertEquals("the actual V62 Android helper upgrade must finish at V65",
                DatabaseHelper.VERSION, upgraded.version)

            val locationColumns = tableColumns(upgraded, "vehicle_locations")
            assertTrue("V62 -> V65 must add vehicle_locations.trip_id", "trip_id" in locationColumns)
            assertEquals(3, scalarInt(upgraded, "SELECT COUNT(*) FROM vehicle_locations"))
            assertEquals(3, scalarInt(upgraded, "SELECT COUNT(*) FROM vehicle_trips"))
            assertEquals(1, scalarInt(upgraded, "SELECT COUNT(*) FROM vehicle_expenses"))
            assertEquals(1, scalarInt(upgraded, "SELECT COUNT(*) FROM deliveries"))

            assertEquals("only the uniquely matching same-vehicle/same-day location may be linked",
                1L, scalarLong(upgraded, "SELECT trip_id FROM vehicle_locations WHERE id=1"))
            assertEquals("ambiguous same-day trips must not be guessed", 0L,
                scalarNullOrZero(upgraded, "SELECT trip_id FROM vehicle_locations WHERE id=2"))
            assertEquals("a location without a matching trip must remain unlinked", 0L,
                scalarNullOrZero(upgraded, "SELECT trip_id FROM vehicle_locations WHERE id=3"))

            assertEquals("driver station must be backfilled from its station-scoped party", 1L,
                scalarLong(upgraded, "SELECT station_id FROM drivers WHERE id=1"))
            assertEquals("legacy trip expense linkage must survive", 1L,
                scalarLong(upgraded, "SELECT trip_id FROM vehicle_expenses WHERE id=1"))
            assertEquals(15.0, scalarDouble(upgraded, "SELECT amount FROM vehicle_expenses WHERE id=1"), 0.000001)
            assertEquals(100.0, scalarDouble(upgraded, "SELECT total_amount FROM deliveries WHERE id=1"), 0.000001)

            // V64 -> V65: explicit UTC instants are converted to Asia/Aden, while
            // zone-less wall-clock timestamps retain their original civil date.
            assertEquals("2026-11-17", scalarString(upgraded,
                "SELECT business_day FROM sales_transactions WHERE id=1"))
            assertEquals("2026-11-16", scalarString(upgraded,
                "SELECT business_day FROM sales_transactions WHERE id=2"))
            assertEquals("2026-11-17", scalarString(upgraded,
                "SELECT business_day FROM payments WHERE id=1"))
            assertEquals("2026-11-16", scalarString(upgraded,
                "SELECT business_day FROM payments WHERE id=2"))
            assertEquals("2026-11-17", scalarString(upgraded,
                "SELECT business_day FROM fuel_sales WHERE id=1"))
            assertEquals("2026-11-17", scalarString(upgraded,
                "SELECT business_day FROM stock_movements WHERE id=1"))
            assertEquals("2026-11-16T21:30:00Z", scalarString(upgraded,
                "SELECT created_at FROM sales_transactions WHERE id=1"))
            assertEquals("2026-11-16 23:45:00", scalarString(upgraded,
                "SELECT created_at FROM sales_transactions WHERE id=2"))

            // Financial and operational evidence remains unchanged by the schema upgrade.
            assertEquals(150.0, scalarDouble(upgraded,
                "SELECT SUM(net_amount) FROM sales_transactions"), 0.000001)
            assertEquals(75.0, scalarDouble(upgraded,
                "SELECT SUM(paid_amount) FROM sales_transactions"), 0.000001)
            assertEquals(75.0, scalarDouble(upgraded,
                "SELECT SUM(remaining_amount) FROM sales_transactions"), 0.000001)
            assertEquals(75.0, scalarDouble(upgraded, "SELECT SUM(amount) FROM payments"), 0.000001)
            assertEquals(100.0, scalarDouble(upgraded, "SELECT SUM(total_amount) FROM fuel_sales"), 0.000001)
            assertEquals(15.0, scalarDouble(upgraded, "SELECT SUM(amount) FROM vehicle_expenses"), 0.000001)
            assertEquals(100.0, scalarDouble(upgraded, "SELECT SUM(total_amount) FROM deliveries"), 0.000001)

            assertEquals("each historical trip receives one created lifecycle event",
                3, scalarInt(upgraded,
                    "SELECT COUNT(*) FROM vehicle_trip_events WHERE event_type='created'"))
            assertEquals(3, scalarInt(upgraded, "SELECT COUNT(DISTINCT trip_id) FROM vehicle_trip_events"))
            assertTrue("fleet location/trip index must be installed",
                hasIndex(upgraded, "idx_vehicle_locations_trip_time"))
            assertTrue("vehicle expense idempotency index must be installed",
                hasIndex(upgraded, "idx_vehicle_expenses_station_idempotency"))
            assertEquals("ok", scalarString(upgraded, "PRAGMA integrity_check"))
            assertEquals("upgrade must not introduce foreign-key violations", 0,
                upgraded.rawQuery("PRAGMA foreign_key_check", null).use { it.count })

            val stableCounts = listOf(
                "vehicle_locations", "vehicle_trips", "vehicle_trip_events", "vehicle_expenses",
                "deliveries", "sales_transactions", "payments", "fuel_sales", "stock_movements"
            ).associateWith { table -> scalarInt(upgraded, "SELECT COUNT(*) FROM $table") }
            DatabaseHelper.closeInstance()
            val reopened = DatabaseHelper.getInstance(context).writableDatabase
            assertEquals(65, reopened.version)
            stableCounts.forEach { (table, expected) ->
                assertEquals("reopening an upgraded V65 database must not duplicate or lose $table rows",
                    expected, scalarInt(reopened, "SELECT COUNT(*) FROM $table"))
            }
            assertEquals(3, scalarInt(reopened,
                "SELECT COUNT(*) FROM vehicle_trip_events WHERE event_type='created'"))
            assertEquals("ok", scalarString(reopened, "PRAGMA integrity_check"))
            assertEquals(0, reopened.rawQuery("PRAGMA foreign_key_check", null).use { it.count })
        } finally {
            DatabaseHelper.closeInstance()
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        }
    }

    private fun createSanitizedV62Database(context: Context): File {
        // Use the production helper to create all non-target reference tables, then
        // replace the 12 migration-surface tables with their exact V62 DDL extracted
        // from the real backup. No rows from the backup are copied into this fixture.
        val before = DatabaseHelper.getInstance(context).writableDatabase
        val databaseFile = context.getDatabasePath(DatabaseHelper.DATABASE_NAME)
        val selectedTables = listOf(
            "parties", "vehicles", "drivers", "vehicle_trips", "vehicle_locations",
            "vehicle_expenses", "vehicle_trip_events", "deliveries", "sales_transactions",
            "payments", "fuel_sales", "stock_movements"
        )
        selectedTables.forEach { table ->
            assertEquals("fresh helper database must not contain operational rows in $table",
                0, scalarInt(before, "SELECT COUNT(*) FROM $table"))
        }

        val resourceName = "db/v62_migration_surface_schema.b64"
        val resource = javaClass.classLoader?.getResourceAsStream(resourceName)
            ?: throw AssertionError("Missing backup-derived V62 schema resource: $resourceName")
        val encoded = resource.bufferedReader(StandardCharsets.US_ASCII).use { it.readText().trim() }
        val compressed = Base64.getDecoder().decode(encoded)
        val manifestText = GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(StandardCharsets.UTF_8).use {
            it.readText()
        }
        val manifest = JSONObject(manifestText)
        assertEquals(62, manifest.getInt("source_user_version"))
        assertEquals(247, manifest.getInt("source_table_count"))
        assertEquals("0ee6b902f96d6789c81771cb1781d3839afc3a9fc03094abc8f3bb12987dab47",
            manifest.getString("source_backup_table_schema_sha256"))
        assertEquals("eb732d57e4bf5a70e839342ac2f33d0993bf49f5ef6cb9558cc9df53af5d7579",
            manifest.getString("selected_table_schema_sha256"))

        before.setForeignKeyConstraintsEnabled(false)
        before.beginTransaction()
        try {
            // Drop child tables first. Foreign-key enforcement is disabled only while
            // replacing the empty fixture schema, never during the production upgrade.
            listOf(
                "payments", "fuel_sales", "deliveries", "vehicle_trip_events",
                "vehicle_locations", "vehicle_expenses", "vehicle_trips", "drivers",
                "vehicles", "sales_transactions", "stock_movements", "parties"
            ).forEach { before.execSQL("DROP TABLE IF EXISTS `$it`") }

            val objects = manifest.getJSONArray("objects")
            assertEquals(selectedTables.size, objects.length())
            for (index in 0 until objects.length()) {
                val item = objects.getJSONObject(index)
                assertTrue("unexpected table in backup-derived schema: ${item.getString("name")}",
                    item.getString("name") in selectedTables)
                before.execSQL(item.getString("sql"))
            }
            before.version = 62
            before.setTransactionSuccessful()
        } finally {
            before.endTransaction()
        }
        before.setForeignKeyConstraintsEnabled(true)

        val stationId = before.rawQuery(
            "SELECT id FROM stations WHERE COALESCE(is_deleted,0)=0 ORDER BY id LIMIT 1", null
        ).use { cursor -> assertTrue("fresh database requires a station reference", cursor.moveToFirst()); cursor.getLong(0) }
        val partyTypeId = before.rawQuery(
            "SELECT id FROM party_types WHERE COALESCE(is_deleted,0)=0 ORDER BY id LIMIT 1", null
        ).use { cursor -> assertTrue("fresh database requires a party type reference", cursor.moveToFirst()); cursor.getLong(0) }
        val roleId = before.rawQuery(
            "SELECT id FROM roles WHERE COALESCE(is_deleted,0)=0 ORDER BY id LIMIT 1", null
        ).use { cursor -> assertTrue("fresh database requires a role reference", cursor.moveToFirst()); cursor.getLong(0) }

        before.beginTransaction()
        try {
            // The production onCreate fixture has roles/stations but intentionally no
            // cashier account; create a deterministic synthetic cashier for FK-valid rows.
            val cashierId = before.insertOrThrow("users", null, ContentValues().apply {
                put("uuid", "TEST-CASHIER-UUID"); put("username", "v62_migration_cashier")
                put("password_hash", "test-only-not-a-password"); put("full_name", "Migration Test Cashier")
                put("full_name_ar", "أمين صندوق اختبار الترحيل")
                put("role_id", roleId); put("station_id", stationId); put("is_deleted", 0)
            })
            val partyId = before.insertOrThrow("parties", null, ContentValues().apply {
                put("uuid", "TEST-PARTY-UUID"); put("party_code", "TEST-PARTY")
                put("party_type_id", partyTypeId); put("station_id", stationId)
                put("commercial_name", "Migration Test Party"); put("commercial_name_ar", "جهة اختبار الترحيل")
                put("is_active", 1); put("is_deleted", 0)
            })
            val vehicleIds = (1..2).map { id ->
                before.insertOrThrow("vehicles", null, ContentValues().apply {
                    put("uuid", "TEST-VEHICLE-$id-UUID"); put("vehicle_code", "TEST-VEHICLE-$id")
                    put("party_id", partyId); put("plate_number", "TEST-00$id"); put("plate_country", "Yemen")
                    put("vehicle_type", "truck"); put("brand", "Test Brand"); put("model", "Test Model")
                    put("status", "active"); put("is_deleted", 0)
                })
            }
            val driverId = before.insertOrThrow("drivers", null, ContentValues().apply {
                put("uuid", "TEST-DRIVER-UUID"); put("driver_code", "TEST-DRIVER")
                put("party_id", partyId); put("vehicle_id", vehicleIds[0]); put("full_name", "Migration Test Driver")
                put("full_name_ar", "سائق اختبار الترحيل"); put("status", "active"); put("is_deleted", 0)
            })
            val shiftId = before.insertOrThrow("shifts", null, ContentValues().apply {
                put("uuid", "TEST-SHIFT-UUID"); put("shift_code", "TEST-SHIFT")
                put("station_id", stationId); put("shift_date", "2026-11-16"); put("shift_type", "night")
                put("start_time", "2026-11-16 18:00:00"); put("cashier_id", cashierId); put("status", "open")
            })

            listOf(
                Triple("2026-11-16", "TEST-TRIP-UNIQUE", 1),
                Triple("2026-11-17", "TEST-TRIP-AMBIGUOUS-A", 2),
                Triple("2026-11-17", "TEST-TRIP-AMBIGUOUS-B", 3)
            ).forEach { (date, uuid, id) ->
                before.insertOrThrow("vehicle_trips", null, ContentValues().apply {
                    put("uuid", uuid); put("vehicle_id", vehicleIds[0]); put("driver_id", driverId)
                    put("trip_date", date); put("start_location", "Test Start $id"); put("end_location", "Test End $id")
                    put("distance_km", 10.0 + id); put("fuel_consumed", 2.0); put("fuel_cost", 10.0)
                    put("trip_purpose", "Synthetic V62 migration test"); put("created_at", "$date 07:00:00")
                })
            }
            listOf(
                Triple(vehicleIds[0], "2026-11-16 08:00:00", 1),
                Triple(vehicleIds[0], "2026-11-17 08:30:00", 2),
                Triple(vehicleIds[1], "2026-11-18 08:30:00", 3)
            ).forEach { (vehicleId, time, id) ->
                before.insertOrThrow("vehicle_locations", null, ContentValues().apply {
                    put("uuid", "TEST-LOCATION-$id"); put("vehicle_id", vehicleId)
                    put("latitude", 15.1000 + id / 10000.0); put("longitude", 44.1000 + id / 10000.0)
                    put("speed", 20.0 + id); put("location_time", time); put("created_at", time)
                })
            }
            before.insertOrThrow("vehicle_expenses", null, ContentValues().apply {
                put("uuid", "TEST-VEHICLE-EXPENSE"); put("vehicle_id", vehicleIds[0])
                put("expense_type", "fuel"); put("expense_date", "2026-11-16"); put("amount", 15.0)
                put("description", "Synthetic migration expense"); put("trip_id", 1)
                put("source_distance_km", 12.5); put("idempotency_key", "test-v62-expense-unique")
            })
            before.insertOrThrow("deliveries", null, ContentValues().apply {
                put("uuid", "TEST-DELIVERY"); put("vehicle_id", vehicleIds[0]); put("driver_id", driverId)
                put("delivery_date", "2026-11-16"); put("quantity", 10.0); put("total_amount", 100.0)
                put("status", "delivered"); put("station_id", stationId); put("latitude", 15.1001)
                put("longitude", 44.1001); put("location_source", "fixture")
            })
            listOf(
                Triple("2026-11-16T21:30:00Z", "2026-11-16", 100.0),
                Triple("2026-11-16 23:45:00", "2026-11-16", 50.0)
            ).forEachIndexed { index, (createdAt, day, amount) ->
                before.insertOrThrow("sales_transactions", null, ContentValues().apply {
                    put("uuid", "TEST-SALE-${index + 1}"); put("sale_code", "TEST-SALE-${index + 1}")
                    put("station_id", stationId); put("shift_id", shiftId); put("invoice_number", "TEST-INVOICE-${index + 1}")
                    put("subtotal", amount); put("gross_amount", amount); put("net_amount", amount)
                    put("paid_amount", if (index == 0) 25.0 else 50.0)
                    put("remaining_amount", if (index == 0) 75.0 else 0.0); put("payment_method", "cash")
                    put("payment_status", if (index == 0) "partial" else "paid"); put("status", "completed")
                    put("cashier_id", cashierId); put("created_at", createdAt); put("business_day", day); put("is_deleted", 0)
                })
            }
            listOf(
                Triple(1, "2026-11-16T21:45:00Z", 25.0),
                Triple(2, "2026-11-16 23:45:00", 50.0)
            ).forEach { (saleId, createdAt, amount) ->
                before.insertOrThrow("payments", null, ContentValues().apply {
                    put("uuid", "TEST-PAYMENT-$saleId"); put("payment_code", "TEST-PAYMENT-$saleId")
                    put("sale_id", saleId); put("payment_type", "cash"); put("payment_method", "cash")
                    put("amount", amount); put("status", "completed"); put("created_at", createdAt)
                    put("business_day", "2026-11-16"); put("station_id", stationId); put("is_deleted", 0)
                })
            }
            before.insertOrThrow("fuel_sales", null, ContentValues().apply {
                put("uuid", "TEST-FUEL-SALE"); put("sale_id", 1); put("station_id", stationId)
                put("quantity", 10.0); put("price_per_liter", 10.0); put("total_amount", 100.0)
                putNull("sale_date"); put("created_at", "2026-11-16T22:00:00Z")
                put("business_day", "2026-11-16"); put("is_deleted", 0)
            })
            before.insertOrThrow("stock_movements", null, ContentValues().apply {
                put("uuid", "TEST-STOCK-MOVEMENT"); put("movement_type", "in")
                put("quantity", 3.0); put("unit_cost", 5.0); put("total_cost", 15.0)
                putNull("movement_date"); put("created_at", "2026-11-16T22:15:00Z")
                put("business_day", "2026-11-16"); put("is_deleted", 0)
            })
            before.setTransactionSuccessful()
        } finally {
            before.endTransaction()
        }
        before.version = 62
        DatabaseHelper.closeInstance()
        return databaseFile
    }

    private fun tableColumns(database: SQLiteDatabase, table: String): Set<String> =
        database.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }

    private fun hasIndex(database: SQLiteDatabase, name: String): Boolean =
        database.rawQuery("SELECT 1 FROM sqlite_master WHERE type='index' AND name=?", arrayOf(name)).use {
            it.moveToFirst()
        }

    private fun scalarInt(database: SQLiteDatabase, sql: String): Int =
        database.rawQuery(sql, null).use { assertTrue(it.moveToFirst()); it.getInt(0) }

    private fun scalarLong(database: SQLiteDatabase, sql: String): Long =
        database.rawQuery(sql, null).use { assertTrue(it.moveToFirst()); it.getLong(0) }

    private fun scalarNullOrZero(database: SQLiteDatabase, sql: String): Long =
        database.rawQuery(sql, null).use { assertTrue(it.moveToFirst()); if (it.isNull(0)) 0L else it.getLong(0) }

    private fun scalarDouble(database: SQLiteDatabase, sql: String): Double =
        database.rawQuery(sql, null).use { assertTrue(it.moveToFirst()); it.getDouble(0) }

    private fun scalarString(database: SQLiteDatabase, sql: String): String =
        database.rawQuery(sql, null).use { assertTrue(it.moveToFirst()); it.getString(0) }
}
