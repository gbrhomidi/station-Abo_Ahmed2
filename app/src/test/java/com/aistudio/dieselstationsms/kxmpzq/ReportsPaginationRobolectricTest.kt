package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportsPaginationRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private lateinit var db: SQLiteDatabase
    private var stationId: Int = 0
    private var userId: Long = 0L
    private var pumpId: Long = 0L
    private var nozzleId: Long = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        db = helper.writableDatabase

        stationId = scalarLong("SELECT id FROM stations ORDER BY id LIMIT 1").toInt()
        userId = optionalScalarLong("SELECT id FROM users WHERE is_deleted = 0 ORDER BY id LIMIT 1")
        if (userId <= 0L) {
            val roleId = scalarLong("SELECT id FROM roles ORDER BY id LIMIT 1")
            userId = db.insertOrThrow("users", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("username", "reports-test-" + UUID.randomUUID().toString().take(8))
                put("password_hash", "test-only")
                put("password_salt", "test-only")
                put("full_name", "Reports Pagination Test")
                put("role_id", roleId)
                put("station_id", stationId)
                put("company_id", 1L)
                put("status", "active")
                put("is_deleted", 0)
            })
        }
        pumpId = optionalScalarLong(
            "SELECT id FROM pumps WHERE station_id = ? AND COALESCE(is_deleted, 0) = 0 ORDER BY id LIMIT 1",
            arrayOf(stationId.toString())
        )
        if (pumpId <= 0L) {
            var tankId = optionalScalarLong(
                "SELECT id FROM tanks WHERE station_id = ? AND COALESCE(is_deleted, 0) = 0 ORDER BY id LIMIT 1",
                arrayOf(stationId.toString())
            )
            if (tankId <= 0L) {
                val fuelTypeId = scalarLong("SELECT id FROM fuel_types WHERE COALESCE(is_deleted, 0) = 0 ORDER BY id LIMIT 1")
                tankId = db.insertOrThrow("tanks", null, ContentValues().apply {
                    put("uuid", UUID.randomUUID().toString())
                    put("tank_code", "RPT-TANK-" + UUID.randomUUID().toString().take(8))
                    put("tank_name", "Reports Pagination Test Tank")
                    put("station_id", stationId)
                    put("fuel_type_id", fuelTypeId)
                    put("capacity_liters", 50000.0)
                    put("status", "active")
                    put("is_deleted", 0)
                })
            }
            pumpId = db.insertOrThrow("pumps", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("pump_code", "RPT-PUMP-" + UUID.randomUUID().toString().take(8))
                put("pump_number", "R")
                put("station_id", stationId)
                put("tank_id", tankId)
                put("status", "active")
                put("is_deleted", 0)
            })
        }
        val existingNozzle = optionalScalarLong(
            "SELECT id FROM pump_nozzles WHERE pump_id = ? AND COALESCE(is_deleted, 0) = 0 ORDER BY id LIMIT 1",
            arrayOf(pumpId.toString())
        )
        nozzleId = if (existingNozzle > 0L) existingNozzle else {
            val fuelTypeId = scalarLong("SELECT id FROM fuel_types WHERE is_deleted = 0 ORDER BY id LIMIT 1")
            db.insertOrThrow("pump_nozzles", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("nozzle_code", "RPT-NOZ-" + UUID.randomUUID().toString().take(8))
                put("nozzle_number", "R")
                put("pump_id", pumpId)
                put("fuel_type_id", fuelTypeId)
                put("status", "active")
                put("is_deleted", 0)
            })
        }
        check(stationId > 0 && userId > 0L && pumpId > 0L && nozzleId > 0L) {
            "Report pagination fixture requires a seeded station, user, pump and nozzle"
        }
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun meterReadingReportUsesSqliteCountAndStableBoundedPages() {
        insertReading("2026-11-01", "morning", balanced = true, status = "approved", sold = 10.0)
        insertReading("2026-11-02", "evening", balanced = false, status = "draft", sold = 20.0)
        insertReading("2026-11-03", "morning", balanced = true, status = "verified", sold = 30.0)
        insertReading("2026-11-04", "night", balanced = true, status = "approved", sold = 40.0, deleted = true)

        val first = helper.getOperationalReport(
            "meter_readings",
            JSONObject()
                .put("station_id", stationId)
                .put("limit", 2)
                .put("offset", 0)
        )
        assertEquals(3, first.getInt("total_count"))
        assertEquals(2, first.getInt("count"))
        assertEquals(1, first.getInt("page"))
        assertEquals(2, first.getInt("total_pages"))
        assertTrue(first.getBoolean("has_next"))
        assertFalse(first.getBoolean("has_previous"))

        val second = helper.getOperationalReport(
            "meter_readings",
            JSONObject()
                .put("station_id", stationId)
                .put("limit", 2)
                .put("offset", 2)
        )
        assertEquals(3, second.getInt("total_count"))
        assertEquals(1, second.getInt("count"))
        assertEquals(2, second.getInt("page"))
        assertTrue(second.getBoolean("has_previous"))
        assertFalse(second.getBoolean("has_next"))

        val beyondLastPage = helper.getOperationalReport(
            "meter_readings",
            JSONObject().put("station_id", stationId).put("limit", 2).put("offset", 1000)
        )
        assertEquals(2, beyondLastPage.getInt("page"))
        assertEquals(1, beyondLastPage.getInt("count"))
        assertEquals(1, beyondLastPage.getJSONArray("rows").length())

        val firstIds = (0 until first.getJSONArray("rows").length())
            .map { first.getJSONArray("rows").getJSONObject(it).getLong("id") }
        val secondId = second.getJSONArray("rows").getJSONObject(0).getLong("id")
        assertEquals(3, (firstIds + secondId).distinct().size)
        assertEquals(60.0, first.getJSONObject("totals").getDouble("sold_liters"), 0.001)
        assertEquals(60.0, second.getJSONObject("totals").getDouble("sold_liters"), 0.001)

        val invalidLimit = helper.getOperationalReport(
            "meter_readings",
            JSONObject().put("station_id", stationId).put("limit", 0).put("offset", 0)
        )
        assertEquals("A zero page size must use the bounded default", 200, invalidLimit.getInt("page_size"))
        assertEquals(3, invalidLimit.getInt("count"))
    }

    @Test
    fun meterReadingFiltersAreSharedByRowsCountAndAggregates() {
        insertReading("2026-11-01", "morning", balanced = true, status = "approved", sold = 10.0)
        insertReading("2026-11-02", "evening", balanced = false, status = "draft", sold = 20.0)
        insertReading("2026-11-03", "morning", balanced = true, status = "approved", sold = 30.0)

        val result = helper.getOperationalReport(
            "meter_readings",
            JSONObject()
                .put("station_id", stationId)
                .put("pump_id", pumpId)
                .put("period", "morning")
                .put("balance", "balanced")
                .put("status", "approved")
                .put("from_date", "2026-11-01")
                .put("to_date", "2026-11-03")
                .put("limit", 50)
                .put("offset", 0)
        )

        assertEquals(2, result.getInt("total_count"))
        assertEquals(2, result.getInt("count"))
        assertEquals(2, result.getJSONObject("totals").getInt("balanced_count"))
        assertEquals(0, result.getJSONObject("totals").getInt("unbalanced_count"))
        assertEquals(40.0, result.getJSONObject("totals").getDouble("sold_liters"), 0.001)
    }

    @Test
    fun meterReadingReportCannotReturnRowsForAnotherStationScope() {
        insertReading("2026-11-01", "morning", balanced = true, status = "approved", sold = 10.0)

        val result = helper.getOperationalReport(
            "meter_readings",
            JSONObject()
                .put("station_id", stationId + 100000)
                .put("limit", 10)
                .put("offset", 0)
        )

        assertEquals(0, result.getInt("total_count"))
        assertEquals(0, result.getInt("count"))
        assertEquals(0, result.getJSONArray("rows").length())
        assertEquals(0, result.getInt("total_pages"))
    }


    @Test
    fun meterReadingTankAndFuelTypeFiltersCannotCrossStationHardware() {
        val fuelTypeId = scalarLong(
            "SELECT id FROM fuel_types WHERE COALESCE(is_deleted, 0) = 0 ORDER BY id LIMIT 1"
        )
        val foreignStationId = db.insertOrThrow("stations", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("station_code", "RPT-FOREIGN-" + UUID.randomUUID().toString().take(8))
            put("station_name", "Foreign station for report scope")
            put("station_name_ar", "محطة أخرى لاختبار عزل القراءات")
            put("status", "active")
            put("is_deleted", 0)
        })
        val foreignTankId = db.insertOrThrow("tanks", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("tank_code", "RPT-FOREIGN-TANK-" + UUID.randomUUID().toString().take(8))
            put("tank_name", "Foreign station tank")
            put("tank_name_ar", "خزان المحطة الأخرى")
            put("station_id", foreignStationId)
            put("fuel_type_id", fuelTypeId)
            put("capacity_liters", 10000.0)
            put("minimum_level", 500.0)
            put("maximum_level", 10000.0)
            put("current_quantity", 5000.0)
            put("status", "active")
            put("is_deleted", 0)
        })
        val foreignPumpId = db.insertOrThrow("pumps", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("pump_code", "RPT-FOREIGN-PUMP-" + UUID.randomUUID().toString().take(8))
            put("pump_number", "F")
            put("station_id", foreignStationId)
            put("tank_id", foreignTankId)
            put("status", "active")
            put("is_deleted", 0)
        })
        val foreignNozzleId = db.insertOrThrow("pump_nozzles", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("nozzle_code", "RPT-FOREIGN-NOZ-" + UUID.randomUUID().toString().take(8))
            put("nozzle_number", "F")
            put("pump_id", foreignPumpId)
            put("fuel_type_id", fuelTypeId)
            put("status", "active")
            put("is_deleted", 0)
        })

        // Deliberately seed a row whose station_id is the active station but whose
        // pump/nozzle references belong to another station. SQLite's individual FKs
        // permit this; report predicates must still prevent the cross-station relation.
        db.insertOrThrow("meter_readings", null, ContentValues().apply {
            val token = UUID.randomUUID().toString()
            put("uuid", token)
            put("reading_code", "RPT-CROSS-STATION-$token")
            put("pump_id", foreignPumpId)
            put("nozzle_id", foreignNozzleId)
            put("station_id", stationId)
            put("reading_date", "2026-11-03")
            put("period", "morning")
            put("opening_reading", 100.0)
            put("closing_reading", 110.0)
            put("sold_liters", 10.0)
            put("system_sold_liters", 10.0)
            put("difference", 0.0)
            put("difference_percent", 0.0)
            put("is_balanced", 1)
            put("read_by", userId)
            put("status", "verified")
            put("is_deleted", 0)
        })

        val byForeignTank = helper.getOperationalReport(
            "meter_readings",
            JSONObject().put("station_id", stationId).put("tank_id", foreignTankId).put("limit", 50)
        )
        assertEquals(0, byForeignTank.getInt("total_count"))
        assertEquals(0, byForeignTank.getJSONArray("rows").length())

        val byFuelType = helper.getOperationalReport(
            "meter_readings",
            JSONObject().put("station_id", stationId).put("fuel_type_id", fuelTypeId).put("limit", 50)
        )
        assertEquals(0, byFuelType.getInt("total_count"))
        assertEquals(0, byFuelType.getJSONArray("rows").length())
    }

    private fun insertReading(
        date: String,
        period: String,
        balanced: Boolean,
        status: String,
        sold: Double,
        deleted: Boolean = false
    ): Long {
        val opening = 1000.0
        val difference = if (balanced) 0.0 else 1.0
        val token = UUID.randomUUID().toString()
        return db.insertOrThrow("meter_readings", null, ContentValues().apply {
            put("uuid", token)
            put("reading_code", "RPT-$token")
            put("pump_id", pumpId)
            put("nozzle_id", nozzleId)
            put("station_id", stationId)
            put("reading_date", date)
            put("period", period)
            put("opening_reading", opening)
            put("closing_reading", opening + sold)
            put("sold_liters", sold)
            put("system_sold_liters", sold + difference)
            put("difference", difference)
            put("difference_percent", difference)
            put("is_balanced", if (balanced) 1 else 0)
            put("tolerance_limit", 0.5)
            put("adjustment_amount", 0.0)
            put("read_by", userId)
            put("status", status)
            put("is_deleted", if (deleted) 1 else 0)
            put("created_at", "$date 12:00:00")
            put("updated_at", "$date 12:00:00")
        })
    }

    private fun scalarLong(sql: String, args: Array<String> = emptyArray()): Long =
        db.rawQuery(sql, args).use { cursor ->
            check(cursor.moveToFirst()) { "No fixture row for query: $sql" }
            cursor.getLong(0)
        }

    private fun optionalScalarLong(sql: String, args: Array<String> = emptyArray()): Long =
        db.rawQuery(sql, args).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
}
