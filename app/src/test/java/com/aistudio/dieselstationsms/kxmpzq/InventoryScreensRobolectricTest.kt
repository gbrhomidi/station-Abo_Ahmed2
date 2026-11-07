package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
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
class InventoryScreensRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private var actorId: Long = 0L
    private var stationTwoActorId: Long = 0L

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
        stationTwoActorId = db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", "inventory-screen-station2-${UUID.randomUUID().toString().take(8)}")
            put("password_hash", "test-only")
            put("full_name", "اختبار محطة ثانية")
            put("role_id", roleId)
            put("station_id", 2)
        })
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    private fun createProduct(quantity: Double, minimum: Double, stationId: Int = 1, userId: Long = actorId): Long {
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
            .put("minimum_stock", minimum), stationId, userId)
        helper.adjustProductStock(productId, quantity, stationId, userId)
        return productId
    }

    private fun addLegacyReturn(productId: Long, warehouseId: Long, referenceType: String?, referenceId: Long): Long {
        val data = JSONObject()
            .put("product_id", productId)
            .put("warehouse_id", warehouseId)
            .put("movement_type", "return")
            .put("quantity", 2.5)
            .put("unit_cost", 10.0)
            .put("reference_id", referenceId)
            .put("notes", "اختبار حركة مرتجع")
        if (referenceType != null) data.put("reference_type", referenceType)
        return helper.addStockMovement(data, 1, actorId)
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
        val listedWarehouse = (0 until stationOneRows.length()).map { stationOneRows.getJSONObject(it) }
            .first { it.optLong("id") == warehouseId }
        assertTrue("Warehouse creation timestamp must be available to reports and sorting", listedWarehouse.optString("created_at").isNotBlank())
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
        // Use the real stock-movement path: it updates an existing product/warehouse
        // level when present and inserts one only when absent (UNIQUE-safe).
        helper.addStockMovement(
            JSONObject()
                .put("product_id", productId)
                .put("warehouse_id", warehouseId)
                .put("movement_type", "in")
                .put("movement_subtype", "in")
                .put("quantity", 3.0)
                .put("unit_cost", 10.0)
                .put("notes", "رصيد اختبار يمنع إيقاف المستودع"),
            1,
            actorId
        )
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
        assertTrue("Alert date must be part of the screen's SQLite row contract", productAlert.optString("created_at").isNotBlank())
        val alertDay = productAlert.getString("created_at").take(10)
        val dateScoped = helper.getStockAlertRecordsContract(
            JSONObject().put("limit", 0).put("from_date", alertDay).put("to_date", alertDay), 1
        )
        assertTrue((0 until dateScoped.getJSONArray("rows").length()).any {
            dateScoped.getJSONArray("rows").getJSONObject(it).optLong("product_id") == productId
        })
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


    @Test
    fun deletingLegacyReturnReversesInventoryAndSoftDeletesAtomically() {
        val productId = createProduct(0.0, 1.0)
        val warehouseId = helper.ensureOperationalWarehouse(1)
        val db = helper.writableDatabase
        val returnId = helper.addStockMovement(
            JSONObject()
                .put("product_id", productId)
                .put("warehouse_id", warehouseId)
                .put("movement_type", "return")
                .put("quantity", 2.5)
                .put("unit_cost", 10.0)
                .put("reference_type", "legacy_return")
                .put("reference_id", 987654L)
                .put("notes", "اختبار عكس المرتجع"),
            1,
            actorId
        )
        val before = db.rawQuery(
            "SELECT quantity_on_hand FROM inventory_levels WHERE product_id=? AND warehouse_id=?",
            arrayOf(productId.toString(), warehouseId.toString())
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getDouble(0) }
        assertEquals(2.5, before, 0.000001)

        assertEquals("a different station must not match the id/station placeholders", 0, helper.deleteReturn(returnId, 2))
        assertEquals(2.5, db.rawQuery(
            "SELECT quantity_on_hand FROM inventory_levels WHERE product_id=? AND warehouse_id=?",
            arrayOf(productId.toString(), warehouseId.toString())
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getDouble(0) }, 0.000001)
        assertEquals(1, helper.deleteReturn(returnId, 1))
        assertEquals("retry after a successful delete must not create another reversal", 0, helper.deleteReturn(returnId, 1))

        val after = db.rawQuery(
            "SELECT quantity_on_hand FROM inventory_levels WHERE product_id=? AND warehouse_id=?",
            arrayOf(productId.toString(), warehouseId.toString())
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getDouble(0) }
        assertEquals(0.0, after, 0.000001)
        db.rawQuery(
            "SELECT is_deleted,status FROM inventory_movements WHERE id=? AND station_id=?",
            arrayOf(returnId.toString(), "1")
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals("cancelled", cursor.getString(1))
        }
        db.rawQuery(
            "SELECT warehouse_id FROM inventory_movements WHERE station_id=? AND movement_type='out' AND reference_type='sale_return_reversal' AND reference_id=? AND is_deleted=0",
            arrayOf("1", "987654")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(warehouseId, cursor.getLong(0))
            assertEquals(1, cursor.count)
        }
        assertTrue(db.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() })
    }

    @Test
    fun deletingFinancialSaleReturnIsRejectedWithoutChangingInventoryOrTheOriginalMovement() {
        val productId = createProduct(0.0, 1.0)
        val warehouseId = helper.ensureOperationalWarehouse(1)
        val db = helper.writableDatabase
        val returnId = helper.addStockMovement(
            JSONObject()
                .put("product_id", productId)
                .put("warehouse_id", warehouseId)
                .put("movement_type", "return")
                .put("quantity", 2.5)
                .put("unit_cost", 10.0)
                .put("reference_type", "sale_return")
                .put("reference_id", 987655L)
                .put("notes", "مرتجع مالي لا يجوز عكس مخزونه منفرداً"),
            1,
            actorId
        )

        val rejected = runCatching { helper.deleteReturn(returnId, 1) }.isFailure
        assertTrue("A sale return must not be reversed by changing inventory alone", rejected)
        assertEquals(
            "financial return facts must not be edited through the legacy movement endpoint",
            0,
            helper.updateReturn(returnId, JSONObject().put("station_id", 1).put("reason", "تعديل غير مسموح"))
        )
        val displayed = helper.getReturns(JSONObject().put("station_id", 1))
            .let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getLong("id") == returnId } }
        assertEquals("sale_return", displayed.getString("reference_type"))
        assertEquals(25.0, displayed.getDouble("stock_cost"), 0.000001)
        assertEquals(2.5, db.rawQuery(
            "SELECT quantity_on_hand FROM inventory_levels WHERE product_id=? AND warehouse_id=?",
            arrayOf(productId.toString(), warehouseId.toString())
        ).use { check(it.moveToFirst()); it.getDouble(0) }, 0.000001)
        db.rawQuery(
            "SELECT is_deleted,status FROM inventory_movements WHERE id=? AND station_id=?",
            arrayOf(returnId.toString(), "1")
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertEquals("completed", cursor.getString(1))
        }
    }

    @Test
    fun deletingOrUpdatingUnknownReturnReferenceFailsClosed() {
        val productId = createProduct(0.0, 1.0)
        val warehouseId = helper.ensureOperationalWarehouse(1)
        val db = helper.writableDatabase
        val unknownId = addLegacyReturn(productId, warehouseId, "imported_unknown_v5", 987656L)
        val emptyId = addLegacyReturn(productId, warehouseId, "", 987657L)
        val nullId = addLegacyReturn(productId, warehouseId, null, 987658L)
        assertEquals(1, db.update("inventory_movements", ContentValues().apply { putNull("reference_type") },
            "id=? AND station_id=?", arrayOf(nullId.toString(), "1")))

        listOf(unknownId, emptyId, nullId).forEach { id ->
            assertTrue("unclassified returns must not be reversed", runCatching { helper.deleteReturn(id, 1) }.isFailure)
            assertEquals("unclassified returns must not be edited", 0,
                helper.updateReturn(id, JSONObject().put("station_id", 1).put("reason", "غير مسموح")))
            db.rawQuery("SELECT is_deleted FROM inventory_movements WHERE id=? AND station_id=?", arrayOf(id.toString(), "1")).use {
                check(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
        assertEquals(7.5, db.rawQuery(
            "SELECT quantity_on_hand FROM inventory_levels WHERE product_id=? AND warehouse_id=?",
            arrayOf(productId.toString(), warehouseId.toString())
        ).use { check(it.moveToFirst()); it.getDouble(0) }, 0.000001)
        assertEquals(0, db.rawQuery("SELECT COUNT(*) FROM inventory_movements WHERE reference_type='sale_return_reversal'", null)
            .use { check(it.moveToFirst()); it.getInt(0) })
    }

    @Test
    fun failedReturnOriginUpdateRollsBackCreatedReversalAndInventory() {
        val productId = createProduct(0.0, 1.0)
        val warehouseId = helper.ensureOperationalWarehouse(1)
        val db = helper.writableDatabase
        val returnId = addLegacyReturn(productId, warehouseId, "legacy_return", 987659L)
        db.execSQL("""CREATE TRIGGER test_fail_legacy_return_soft_delete
            BEFORE UPDATE ON inventory_movements
            WHEN OLD.id=$returnId AND OLD.movement_type='return'
            BEGIN SELECT RAISE(ABORT, 'forced original update failure'); END""")

        assertTrue(runCatching { helper.deleteReturn(returnId, 1) }.isFailure)
        assertEquals(2.5, db.rawQuery(
            "SELECT quantity_on_hand FROM inventory_levels WHERE product_id=? AND warehouse_id=?",
            arrayOf(productId.toString(), warehouseId.toString())
        ).use { check(it.moveToFirst()); it.getDouble(0) }, 0.000001)
        assertEquals(0, db.rawQuery("SELECT COUNT(*) FROM inventory_movements WHERE reference_type='sale_return_reversal' AND reference_id=987659", null)
            .use { check(it.moveToFirst()); it.getInt(0) })
        assertEquals(0, db.rawQuery("SELECT is_deleted FROM inventory_movements WHERE id=?", arrayOf(returnId.toString()))
            .use { check(it.moveToFirst()); it.getInt(0) })
        db.execSQL("DROP TRIGGER test_fail_legacy_return_soft_delete")
    }

    @Test
    fun failedReturnReversalInsertRollsBackWithoutChangingOriginalOrInventory() {
        val productId = createProduct(0.0, 1.0)
        val warehouseId = helper.ensureOperationalWarehouse(1)
        val db = helper.writableDatabase
        val returnId = addLegacyReturn(productId, warehouseId, "legacy_return", 987660L)
        db.execSQL("""CREATE TRIGGER test_fail_return_reversal_insert
            BEFORE INSERT ON inventory_movements
            WHEN NEW.reference_type='sale_return_reversal'
            BEGIN SELECT RAISE(ABORT, 'forced reversal insert failure'); END""")

        assertTrue(runCatching { helper.deleteReturn(returnId, 1) }.isFailure)
        assertEquals(2.5, db.rawQuery(
            "SELECT quantity_on_hand FROM inventory_levels WHERE product_id=? AND warehouse_id=?",
            arrayOf(productId.toString(), warehouseId.toString())
        ).use { check(it.moveToFirst()); it.getDouble(0) }, 0.000001)
        assertEquals(0, db.rawQuery("SELECT COUNT(*) FROM inventory_movements WHERE reference_type='sale_return_reversal' AND reference_id=987660", null)
            .use { check(it.moveToFirst()); it.getInt(0) })
        assertEquals(0, db.rawQuery("SELECT is_deleted FROM inventory_movements WHERE id=?", arrayOf(returnId.toString()))
            .use { check(it.moveToFirst()); it.getInt(0) })
        db.execSQL("DROP TRIGGER test_fail_return_reversal_insert")
    }

    @Test
    fun returnScreenUsesNativeAddRouteAndDoesNotSubmitHardCodedIdentity() {
        val html = context.assets.open("screens/returned-products.html").bufferedReader().use { it.readText() }
        assertTrue(html.contains("case 'addReturn': raw = AndroidInterface.addReturn(JSON.stringify(p)); break;"))
        assertTrue(html.contains("item.reference_type === 'sale_return'"))
        assertTrue(html.contains("item.reference_type === 'legacy_return'"))
        assertFalse("station identity must come from the authenticated native bridge", html.contains("station_id: 1"))
        assertFalse("actor identity must come from the authenticated native bridge", html.contains("created_by: 1"))
        assertFalse("the sale return amount is calculated from the invoice, not entered in the form", html.contains("id=\"fValue\""))
    }

    @Test
    fun legacyDashboardReadsTheLowStockKeyReturnedByNativeStats() {
        val html = context.assets.open("screens/dashboard.html").bufferedReader().use { it.readText() }
        assertTrue(html.contains("data.low_stock)"))
        assertFalse(html.contains("data.low_stock_count"))
    }

    @Test
    fun dashboardCountsUniqueLowStockProductsAcrossActiveWarehousesOnlyForTheirStation() {
        val db = helper.writableDatabase
        val before = helper.getDashboardStatsForDate(1, JSONObject(), "2026-11-05").getInt("low_stock")
        val beforeProducts = helper.getDashboardStatsForDate(1, JSONObject(), "2026-11-05").getInt("total_products")
        val stationTwoBefore = helper.getDashboardStatsForDate(2, JSONObject(), "2026-11-05").getInt("low_stock")
        val productId = createProduct(1.0, 10.0)
        val aboveLimitProductId = createProduct(3.3334, 10.0)
        val otherStationProductId = createProduct(0.0, 10.0, 2, stationTwoActorId)
        val firstWarehouse = helper.ensureOperationalWarehouse(1)
        val secondWarehouse = db.insertOrThrow("warehouses", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("station_id", 1)
            put("warehouse_name", "مستودع إضافي ${UUID.randomUUID().toString().take(8)}")
            put("is_active", 1)
        })
        val thirdWarehouse = db.insertOrThrow("warehouses", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("station_id", 1)
            put("warehouse_name", "مستودع ثالث ${UUID.randomUUID().toString().take(8)}")
            put("is_active", 1)
        })
        val inactiveWarehouse = db.insertOrThrow("warehouses", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("station_id", 1)
            put("warehouse_name", "مستودع غير نشط ${UUID.randomUUID().toString().take(8)}")
            put("is_active", 0)
        })
        val otherStationWarehouse = helper.ensureOperationalWarehouse(2)
        assertEquals(1, db.update("inventory_levels", ContentValues().apply { put("quantity_on_hand", 2.0) },
            "product_id=? AND warehouse_id=?", arrayOf(productId.toString(), firstWarehouse.toString())))
        db.insertOrThrow("inventory_levels", null, ContentValues().apply {
            put("product_id", productId); put("warehouse_id", secondWarehouse); put("quantity_on_hand", 3.0)
        })
        db.insertOrThrow("inventory_levels", null, ContentValues().apply {
            put("product_id", productId); put("warehouse_id", thirdWarehouse); put("quantity_on_hand", 5.0)
        })
        db.insertOrThrow("inventory_levels", null, ContentValues().apply {
            put("product_id", aboveLimitProductId); put("warehouse_id", secondWarehouse); put("quantity_on_hand", 3.3333)
        })
        db.insertOrThrow("inventory_levels", null, ContentValues().apply {
            put("product_id", aboveLimitProductId); put("warehouse_id", thirdWarehouse); put("quantity_on_hand", 3.3334)
        })
        db.insertOrThrow("inventory_levels", null, ContentValues().apply {
            put("product_id", productId); put("warehouse_id", inactiveWarehouse); put("quantity_on_hand", 100.0)
        })
        db.insertOrThrow("inventory_levels", null, ContentValues().apply {
            put("product_id", productId); put("warehouse_id", otherStationWarehouse); put("quantity_on_hand", 100.0)
        })

        val scopedRows = db.rawQuery(
            "SELECT il.quantity_on_hand FROM inventory_levels il JOIN warehouses w ON w.id=il.warehouse_id WHERE il.product_id=? AND w.station_id=1 AND w.is_active=1",
            arrayOf(productId.toString())
        ).use { cursor ->
            val quantities = mutableListOf<Double>()
            while (cursor.moveToNext()) quantities += cursor.getDouble(0)
            quantities
        }
        assertEquals(3, scopedRows.size)
        assertEquals("three active warehouses sum to the exact minimum boundary", 10.0, scopedRows.sum(), 0.000001)
        val aboveLimitRows = db.rawQuery(
            "SELECT il.quantity_on_hand FROM inventory_levels il JOIN warehouses w ON w.id=il.warehouse_id WHERE il.product_id=? AND w.station_id=1 AND w.is_active=1",
            arrayOf(aboveLimitProductId.toString())
        ).use { cursor ->
            val quantities = mutableListOf<Double>()
            while (cursor.moveToNext()) quantities += cursor.getDouble(0)
            quantities
        }
        assertEquals(3, aboveLimitRows.size)
        assertEquals("independent active-warehouse reference puts the second product just above minimum stock",
            10.0001, aboveLimitRows.sum(), 0.000001)
        val after = helper.getDashboardStatsForDate(1, JSONObject(), "2026-11-05")
        assertEquals("Only the product exactly at its minimum is low; a second product just above it is not", before + 1, after.getInt("low_stock"))
        assertEquals(beforeProducts + 2, after.getInt("total_products"))
        assertEquals("a low-stock product and foreign warehouse balance in station 1 must not leak into station 2", stationTwoBefore + 1,
            helper.getDashboardStatsForDate(2, JSONObject(), "2026-11-05").getInt("low_stock"))
        assertTrue(otherStationProductId > 0L)
    }

}
