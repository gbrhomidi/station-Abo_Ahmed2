package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InventoryScreensRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private var actorId: Long = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        val db = helper.writableDatabase
        val roleId = db.rawQuery("SELECT id FROM roles ORDER BY id LIMIT 1", null).use {
            check(it.moveToFirst()); it.getLong(0)
        }
        actorId = db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", "inventory-screen-${UUID.randomUUID().toString().take(8)}")
            put("password_hash", "test-only")
            put("full_name", "اختبار شاشات المخزون")
            put("role_id", roleId)
            put("station_id", 1)
        })
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    private fun createProduct(quantity: Double, minimum: Double): Long {
        val db = helper.writableDatabase
        val categoryId = db.rawQuery("SELECT id FROM product_categories WHERE is_deleted = 0 ORDER BY id LIMIT 1", null).use {
            check(it.moveToFirst()); it.getLong(0)
        }
        val unitId = db.rawQuery("SELECT id FROM units ORDER BY id LIMIT 1", null).use {
            check(it.moveToFirst()); it.getLong(0)
        }
        val productId = helper.insertProduct(JSONObject()
            .put("product_name", "Inventory screen test")
            .put("product_name_ar", "اختبار شاشة المخزون")
            .put("category_id", categoryId)
            .put("unit_id", unitId)
            .put("purchase_price", 10.0)
            .put("sale_price", 12.0)
            .put("quantity", quantity)
            .put("minimum_stock", minimum), 1, actorId)
        helper.adjustProductStock(productId, quantity, 1, actorId)
        return productId
    }

    private fun createLowFuelTank(stationId: Int, codeSuffix: String): Long {
        val db = helper.writableDatabase
        val uniqueCode = "TST-" + stationId + "-" + codeSuffix + "-" + UUID.randomUUID().toString().take(5)
        return db.insertOrThrow("tanks", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("tank_code", uniqueCode)
            put("tank_name", uniqueCode)
            put("tank_name_ar", "خزان اختبار " + uniqueCode)
            put("station_id", stationId)
            put("fuel_type_id", 1)
            put("capacity_liters", 10000.0)
            put("minimum_level", 1500.0)
            put("maximum_level", 10000.0)
            put("current_quantity", 250.0)
            put("status", "active")
        })
    }

    @Test
    fun stockLevelsReturnRealRowsAndStatisticsFromSqlite() {
        val productId = createProduct(5.0, 2.0)
        val page = helper.getStockLevelsPage(JSONObject().put("stock_type", "products").put("limit", 100), 1)
        assertTrue(page.getInt("total_count") >= 1)
        val rows = page.getJSONArray("rows")
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }
            .first { it.getLong("product_id") == productId }
        assertEquals(5.0, row.getDouble("quantity"), 0.0001)
        assertEquals(50.0, row.getDouble("stock_value"), 0.0001)
        assertTrue(page.getJSONObject("stats").getDouble("total_quantity") >= 5.0)
        assertTrue(page.getJSONObject("stats").getDouble("total_value") >= 50.0)
    }

    @Test
    fun inventoryMovementsReturnRealRowsStatsAndArchiveOnlyScopedRows() {
        val productId = createProduct(5.0, 2.0)
        val warehouseId = helper.ensureOperationalWarehouse(1)
        val movementId = helper.addStockMovement(JSONObject()
            .put("product_id", productId)
            .put("warehouse_id", warehouseId)
            .put("movement_type", "in")
            .put("movement_subtype", "in")
            .put("quantity", 2.0)
            .put("unit_cost", 10.0)
            .put("notes", "اختبار حركة حقيقية"), 1, actorId)

        val page = helper.getUnifiedInventoryMovements(
            JSONObject().put("stock_type", "products").put("product_id", productId).put("limit", 50), 1
        )
        assertTrue((0 until page.getJSONArray("rows").length()).any {
            page.getJSONArray("rows").getJSONObject(it).getLong("movement_id") == movementId
        })
        assertTrue(page.getInt("total_count") >= 1)
        val stats = helper.getUnifiedInventoryMovementStats(JSONObject().put("stock_type", "products").put("product_id", productId), 1)
        assertTrue(stats.getInt("total_movements") >= 1)
        assertTrue(stats.getInt("inbound_count") >= 1)

        assertEquals(1, helper.archiveStockMovement(movementId, actorId, 1))
        val afterArchive = helper.getUnifiedInventoryMovements(
            JSONObject().put("stock_type", "products").put("product_id", productId).put("limit", 50), 1
        )
        assertTrue((0 until afterArchive.getJSONArray("rows").length()).none {
            afterArchive.getJSONArray("rows").getJSONObject(it).getLong("movement_id") == movementId
        })
    }

    @Test
    fun stockAlertsSynchronizeResolveAndRemainBackedBySqlite() {
        val productId = createProduct(0.0, 2.0)
        val result = helper.getStockAlertRecordsContract(JSONObject().put("limit", 50), 1)
        val rows = result.getJSONArray("rows")
        val alert = (0 until rows.length()).map { rows.getJSONObject(it) }
            .first { it.getLong("product_id") == productId && it.getInt("is_resolved") == 0 }
        assertEquals("critical", alert.getString("alert_level"))
        assertEquals("out_of_stock", alert.getString("alert_type"))
        assertEquals("اختبار شاشة المخزون", alert.getString("product_name"))
        assertTrue(result.getJSONObject("statistics").getInt("unresolved_count") >= 1)

        val resolved = helper.resolveOperationalRecord(
            "stock_alerts", alert.getLong("id"), "تمت المراجعة", actorId, 1
        )
        assertEquals(1, resolved)

        val refreshed = helper.getStockAlertRecordsContract(JSONObject().put("limit", 50).put("status", "resolved"), 1)
        assertTrue((0 until refreshed.getJSONArray("rows").length()).any {
            val row = refreshed.getJSONArray("rows").getJSONObject(it)
            row.getLong("id") == alert.getLong("id") && row.getInt("is_resolved") == 1
        })
        assertTrue(helper.writableDatabase.rawQuery(
            "PRAGMA foreign_key_check", null
        ).use { !it.moveToFirst() })
    }

    @Test
    fun warehouseCrudIsStationScopedAndDeactivationPreservesLinkedRows() {
        val db = helper.writableDatabase
        val suffix = UUID.randomUUID().toString().take(8)
        val warehouseName = "اختبار مستودع " + suffix
        val warehouseId = helper.saveOperationalRecord(
            "warehouses",
            JSONObject()
                .put("station_id", 1)
                .put("warehouse_name", warehouseName)
                .put("location_details", "مستودع اختبار")
                .put("is_default", 0)
                .put("is_active", 1),
            actorId
        )
        val secondStationWarehouseId = helper.saveOperationalRecord(
            "warehouses",
            JSONObject()
                .put("station_id", 2)
                .put("warehouse_name", "مستودع محطة ثانية " + suffix)
                .put("location_details", "محطة ثانية")
                .put("is_default", 0)
                .put("is_active", 1),
            actorId
        )

        val stationOneRows = helper.getOperationalRows("warehouses", JSONObject().put("station_id", 1).put("limit", 0))
        val stationTwoRows = helper.getOperationalRows("warehouses", JSONObject().put("station_id", 2).put("limit", 0))
        assertTrue((0 until stationOneRows.length()).any { stationOneRows.getJSONObject(it).optLong("id") == warehouseId })
        assertTrue((0 until stationOneRows.length()).none { stationOneRows.getJSONObject(it).optLong("id") == secondStationWarehouseId })
        assertTrue((0 until stationTwoRows.length()).any { stationTwoRows.getJSONObject(it).optLong("id") == secondStationWarehouseId })
        assertTrue((0 until stationTwoRows.length()).none { stationTwoRows.getJSONObject(it).optLong("id") == warehouseId })

        assertEquals(
            0,
            helper.updateOperationalRecord(
                "warehouses",
                warehouseId,
                JSONObject()
                    .put("station_id", 2)
                    .put("warehouse_name", "محاولة تعديل خارج المحطة")
                    .put("location_details", "رفض النطاق")
                    .put("is_default", 0)
                    .put("is_active", 1),
                actorId
            )
        )
        assertEquals(
            1,
            helper.updateOperationalRecord(
                "warehouses",
                warehouseId,
                JSONObject()
                    .put("station_id", 1)
                    .put("warehouse_name", warehouseName + " معدل")
                    .put("location_details", "تم تعديل البيانات")
                    .put("is_default", 0)
                    .put("is_active", 1),
                actorId
            )
        )

        val productId = createProduct(0.0, 2.0)
        db.insertOrThrow("inventory_levels", null, ContentValues().apply {
            put("product_id", productId)
            put("warehouse_id", warehouseId)
            put("quantity_on_hand", 3.0)
            put("quantity_committed", 0.0)
            put("average_cost", 10.0)
        })
        var blockedByBalance = false
        try {
            helper.deleteOperationalRecord("warehouses", warehouseId, actorId, 1)
        } catch (_: IllegalArgumentException) {
            blockedByBalance = true
        }
        assertTrue("Warehouse deactivation must be blocked while stock remains.", blockedByBalance)
        assertEquals(0, helper.deleteOperationalRecord("warehouses", warehouseId, actorId, 2))

        db.update(
            "inventory_levels",
            ContentValues().apply {
                put("quantity_on_hand", 0.0)
                put("quantity_committed", 0.0)
            },
            "warehouse_id = ?",
            arrayOf(warehouseId.toString())
        )
        assertEquals(1, helper.deleteOperationalRecord("warehouses", warehouseId, actorId, 1))
        db.rawQuery(
            "SELECT is_active FROM warehouses WHERE id = ? AND station_id = ?",
            arrayOf(warehouseId.toString(), "1")
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        assertEquals(
            1,
            db.rawQuery("SELECT COUNT(*) FROM inventory_levels WHERE warehouse_id = ?", arrayOf(warehouseId.toString()))
                .use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
        )
        val report = helper.getOperationalReport(
            "warehouses",
            JSONObject().put("station_id", 1).put("limit", 0)
        )
        assertTrue((0 until report.getJSONArray("rows").length()).any {
            report.getJSONArray("rows").getJSONObject(it).optLong("id") == warehouseId
        })
        assertTrue(db.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() })
    }

    @Test
    fun stockAlertContractMergesProductsAndFuelWithStationIsolationAndPagination() {
        val productId = createProduct(0.0, 2.0)
        val stationOneTankId = createLowFuelTank(1, "ONE")
        val stationTwoTankId = createLowFuelTank(2, "TWO")

        val stationOne = helper.getStockAlertRecordsContract(JSONObject().put("limit", 0), 1)
        val stationOneRows = stationOne.getJSONArray("rows")
        val productAlert = (0 until stationOneRows.length()).map { stationOneRows.getJSONObject(it) }
            .first { it.optLong("product_id", 0L) == productId }
        val fuelAlert = (0 until stationOneRows.length()).map { stationOneRows.getJSONObject(it) }
            .first { it.optLong("tank_id", 0L) == stationOneTankId }
        assertEquals("product", productAlert.getString("item_type"))
        assertEquals("fuel", fuelAlert.getString("item_type"))
        assertTrue(fuelAlert.getLong("id") < 0L)
        assertEquals(1L, fuelAlert.getLong("fuel_type_id"))
        assertEquals(stationOneRows.length(), stationOne.getJSONObject("statistics").getInt("total_count"))
        assertTrue(stationOne.getJSONObject("statistics").getInt("fuel_count") >= 1)
        assertTrue(stationOne.getJSONObject("statistics").getInt("product_count") >= 1)
        assertTrue((0 until stationOneRows.length()).none {
            stationOneRows.getJSONObject(it).optLong("tank_id", 0L) == stationTwoTankId
        })

        val stationTwo = helper.getStockAlertRecordsContract(JSONObject().put("limit", 0), 2)
        val stationTwoRows = stationTwo.getJSONArray("rows")
        assertTrue((0 until stationTwoRows.length()).any {
            stationTwoRows.getJSONObject(it).optLong("tank_id", 0L) == stationTwoTankId
        })
        assertTrue((0 until stationTwoRows.length()).none {
            stationTwoRows.getJSONObject(it).optLong("product_id", 0L) == productId
        })

        val page = helper.getStockAlertRecordsContract(JSONObject().put("limit", 1).put("offset", 1), 1)
        assertEquals(stationOne.getInt("total_count"), page.getInt("total_count"))
        assertEquals(1, page.getJSONArray("rows").length())
        assertEquals(stationOne.getInt("total_count"), page.getInt("total_pages"))

        val searched = helper.getStockAlertRecordsContract(JSONObject().put("limit", 0).put("search", "ONE"), 1)
        assertEquals(1, searched.getJSONArray("rows").length())
        assertEquals(stationOneTankId, searched.getJSONArray("rows").getJSONObject(0).getLong("tank_id"))
        assertEquals(1, searched.getJSONObject("statistics").getInt("total_count"))
    }

    @Test
    fun replacementInventoryScreensExposeTheNativeSqliteContracts() {
        val warehouseHtml = context.assets.open("screens/warehouses.html").bufferedReader().use { it.readText() }
        val alertsHtml = context.assets.open("screens/inventory-alerts.html").bufferedReader().use { it.readText() }

        assertTrue(warehouseHtml.contains("getWarehouseRecords"))
        assertTrue(warehouseHtml.contains("generateWarehouseReport"))
        assertTrue(warehouseHtml.contains("saveWarehouseRecord"))
        assertTrue(warehouseHtml.contains("updateWarehouseRecord"))
        assertTrue(warehouseHtml.contains("deleteWarehouseRecord"))
        assertTrue(warehouseHtml.contains("limit: 0"))
        assertTrue(warehouseHtml.contains("getCurrentStationIdForReports"))

        assertTrue(alertsHtml.contains("getStockAlertRecords"))
        assertTrue(alertsHtml.contains("resolveStockAlertRecord"))
        assertTrue(alertsHtml.contains("fuel_type_id"))
        assertTrue(alertsHtml.contains("limit: 0"))
    }

}
