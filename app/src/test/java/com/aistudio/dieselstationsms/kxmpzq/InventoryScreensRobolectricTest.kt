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
}
