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

    private fun prepareReconciliationReadings(tankId: Long, shiftId: Long, openingLiters: Double, closingLiters: Double): Pair<Long, Long> {
        val db=helper.writableDatabase
        val start=db.rawQuery("SELECT start_time FROM shifts WHERE id=?",arrayOf(shiftId.toString())).use {
            check(it.moveToFirst()); it.getString(0)
        }
        val openingAt=java.time.LocalDateTime.parse(start.replace(' ','T')).minusMinutes(5)
            .toString().replace('T',' ')
        db.insertOrThrow("tank_readings",null,ContentValues().apply {
            put("tank_id",tankId); put("reading_at",java.time.LocalDateTime.parse(start.replace(' ','T')).minusDays(2)
                .toString().replace('T',' ')); put("current_liters",openingLiters+500.0)
            put("source","manual"); put("recorded_by",actorId)
        })
        val openingId=db.insertOrThrow("tank_readings",null,ContentValues().apply {
            put("tank_id",tankId); put("reading_at",openingAt); put("current_liters",openingLiters)
            put("source","manual"); put("recorded_by",actorId)
        })
        db.insertOrThrow("tank_readings",null,ContentValues().apply {
            put("tank_id",tankId); put("reading_at",java.time.LocalDateTime.parse(start.replace(' ','T')).plusMinutes(1)
                .toString().replace('T',' ')); put("current_liters",openingLiters+900.0)
            put("source","manual"); put("recorded_by",actorId)
        })
        val dip=helper.insertDipReading(JSONObject().put("tank_id",tankId).put("shift_id",shiftId)
            .put("dip_cm",100.0).put("dip_liters",closingLiters).put("tolerance_threshold",1000.0),1,actorId)
        return openingId to dip.getLong("dip_id")
    }

    @Test
    fun dryStockLocationScopesBatchQuantityAndNearestExpiry() {
        val db = helper.writableDatabase
        val warehouseId = helper.ensureOperationalWarehouse(1)
        val selectedLocation = db.insertOrThrow("stock_locations", null, ContentValues().apply {
            put("warehouse_id", warehouseId); put("name", "DB19 selected"); put("type", "warehouse"); put("is_active", 1)
        })
        val otherLocation = db.insertOrThrow("stock_locations", null, ContentValues().apply {
            put("warehouse_id", warehouseId); put("name", "DB19 other"); put("type", "warehouse"); put("is_active", 1)
        })
        val productId = createProduct(0.0, 1.0)
        val today = java.time.LocalDate.parse(helper.currentDateForBridge())
        fun batch(location: Long, qty: Double, expiry: String) {
            db.insertOrThrow("product_batches", null, ContentValues().apply {
                put("product_id", productId); put("lot_number", "LOC-" + UUID.randomUUID().toString())
                put("expiry_date", expiry); put("quantity", qty); put("location_id", location)
                put("received_at", today.toString())
            })
        }
        batch(selectedLocation, 3.0, today.plusDays(12).toString())
        batch(otherLocation, 9.0, today.plusDays(2).toString())
        val rows = helper.getDryStockPage(JSONObject().put("location_id", selectedLocation), 1)
            .getJSONArray("products")
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }
            .single { it.getLong("product_id") == productId }
        assertEquals(3.0, row.getDouble("batch_quantity"), 0.0001)
        assertEquals(today.plusDays(12).toString(), row.getString("nearest_expiry"))
    }

    @Test
    fun serialReceiptEnforcesProductTrackingBatchWarrantySupplierAndLocationScope() {
        val db = helper.writableDatabase
        val productId = createProduct(0.0, 1.0)
        db.update("products", ContentValues().apply {
            put("track_serial", 1); put("track_batch", 1)
            put("track_expiry", 1); put("track_warranty", 1)
        }, "id=?", arrayOf(productId.toString()))
        val warehouseId = helper.ensureOperationalWarehouse(1)
        val locationId = db.insertOrThrow("stock_locations", null, ContentValues().apply {
            put("warehouse_id", warehouseId); put("name", "DB20 serial location")
            put("type", "warehouse"); put("is_active", 1)
        })
        val lot = "DB20-" + UUID.randomUUID().toString()
        db.insertOrThrow("product_batches", null, ContentValues().apply {
            put("product_id", productId); put("lot_number", lot); put("quantity", 4.0)
            put("location_id", locationId); put("received_at", helper.currentDateForBridge())
        })
        val base = JSONObject().put("product_id", productId).put("serial_no", "SN-" + UUID.randomUUID())
            .put("batch_no", lot).put("location_id", locationId)
        assertTrue(runCatching { helper.insertProductSerial(base, 1, actorId) }.isFailure)
        val complete = JSONObject(base.toString())
            .put("warranty_end_at", helper.currentDateForBridge())
            .put("expiry_at", helper.currentDateForBridge())
        val serialId = helper.insertProductSerial(complete, 1, actorId)
        assertTrue(serialId > 0)
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM product_serials WHERE serial_id=? AND product_id=? AND batch_no=? AND location_id=?",
            arrayOf(serialId.toString(), productId.toString(), lot, locationId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        assertTrue(runCatching {
            helper.insertProductSerial(
                JSONObject(complete.toString()).put("serial_no", "SN-CROSS-" + UUID.randomUUID()),
                2, actorId
            )
        }.isFailure)
    }

    @Test
    fun warrantyExposureIncludesAvailableAndSoldAndUsesStationBusinessDate() {
        val productId = createProduct(2.0, 1.0)
        val db = helper.writableDatabase
        val today = java.time.LocalDate.parse(helper.currentDateForBridge())
        fun serial(status: String, expiry: String) {
            db.insertOrThrow("product_serials", null, ContentValues().apply {
                put("product_id", productId); put("serial_no", "WARRANTY-" + UUID.randomUUID())
                put("status", status); put("warranty_end_at", expiry)
            })
        }
        serial("AVAILABLE", today.plusDays(5).toString())
        serial("SOLD", today.plusDays(5).toString())
        serial("SOLD", today.minusDays(1).toString())
        val rows = helper.getExpiringWarranties(30, 1)
        assertEquals(2, (0 until rows.length()).count {
            rows.getJSONObject(it).optLong("product_id") == productId
        })
    }

    @Test
    fun dryStockSearchAndCategoryFiltersAreAppliedBeforeReturningProducts() {
        val db = helper.writableDatabase
        val productId = createProduct(8.0, 2.0)
        val uniqueName = "DB19-FILTER-" + UUID.randomUUID().toString().take(8)
        assertEquals(1, db.update("products", ContentValues().apply {
            put("product_name", uniqueName)
            put("product_name_ar", uniqueName)
        }, "id=? AND station_id=1", arrayOf(productId.toString())))
        val categoryId = db.rawQuery(
            "SELECT category_id FROM products WHERE id=?", arrayOf(productId.toString())
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }
        val searched = helper.getDryStockPage(
            JSONObject().put("search", uniqueName), 1
        ).getJSONArray("products")
        assertEquals(1, (0 until searched.length()).count {
            searched.getJSONObject(it).optLong("product_id") == productId
        })
        val categorized = helper.getDryStockPage(
            JSONObject().put("category_id", categoryId), 1
        ).getJSONArray("products")
        assertTrue((0 until categorized.length()).any {
            categorized.getJSONObject(it).optLong("product_id") == productId
        })
        val otherStation = helper.getDryStockPage(JSONObject().put("search", uniqueName), 2)
            .getJSONArray("products")
        assertEquals(0, otherStation.length())
    }

    @Test
    fun wetStockReasonCatalogContainsV3MovementReasonsAndScopes() {
        val db = helper.writableDatabase
        val expected = mapOf(
            "RECEIVE" to "BOTH", "TRANSFER" to "BOTH", "DAMAGE" to "PRODUCT",
            "EXPIRY" to "PRODUCT", "RETURN" to "BOTH", "FUEL_SALE" to "FUEL"
        )
        expected.forEach { (code, appliesTo) ->
            db.rawQuery(
                "SELECT applies_to FROM inventory_reason_codes WHERE code=?",
                arrayOf(code)
            ).use { c ->
                assertTrue("Missing V3.0 reason code: $code", c.moveToFirst())
                assertEquals(appliesTo, c.getString(0))
            }
        }
    }

    @Test
    fun shiftReconciliationSubmissionIsDraftAtomicAndUnapproved() {
        val db = helper.writableDatabase
        val tankId = createLowFuelTank(1, "RECON")
        val shiftId = db.insertOrThrow("shifts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("shift_code", "RECON-" + UUID.randomUUID().toString().take(8))
            put("station_id", 1)
            put("shift_date", "2026-11-19")
            put("shift_type", "morning")
            put("start_time", "2026-11-19 06:00:00")
            put("status", "open")
        })
        val payload = JSONObject().put("shift_id", shiftId).put("tank_id", tankId)
            .put("actual_liters", 250.0).put("tolerance_threshold", 0.01)
        val readingIds = prepareReconciliationReadings(tankId, shiftId, 250.0, 250.0)
        val first = helper.submitShiftReconciliation(payload, 1, actorId)
        assertEquals("DRAFT", first.getString("status"))
        assertEquals(readingIds.first, first.getLong("opening_reading_id"))
        assertEquals(readingIds.second, first.getLong("closing_reading_id"))
        assertEquals(250.0, first.getDouble("opening_liters"), 0.0001)
        assertTrue(first.getJSONObject("calculation_window").getString("start").isNotBlank())
        val second = helper.submitShiftReconciliation(payload, 1, actorId)
        assertTrue(second.getLong("recon_id") > first.getLong("recon_id"))
        val count = db.rawQuery(
            "SELECT COUNT(*) FROM shift_reconciliations WHERE shift_id=? AND tank_id=?",
            arrayOf(shiftId.toString(), tankId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(1, count)
        val approved = db.rawQuery(
            "SELECT COUNT(*) FROM shift_reconciliations WHERE shift_id=? AND tank_id=? AND approved_by IS NOT NULL",
            arrayOf(shiftId.toString(), tankId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(0, approved)
    }

    @Test
    fun wetStockReconciliationIntegratesOpeningDeliverySaleAndClosingDipInShiftWindow() {
        val db=helper.writableDatabase
        val tankId=createLowFuelTank(1,"RECON-E2E")
        val shiftId=db.insertOrThrow("shifts",null,ContentValues().apply {
            put("uuid",UUID.randomUUID().toString()); put("shift_code","RECON-E2E-"+UUID.randomUUID().toString().take(8))
            put("station_id",1); put("shift_date","2026-11-19"); put("shift_type","morning")
            put("start_time","2026-11-19 06:00:00"); put("status","open")
        })
        prepareReconciliationReadings(tankId,shiftId,1000.0,950.0)
        val pumpId=db.insertOrThrow("pumps",null,ContentValues().apply {
            put("uuid",UUID.randomUUID().toString()); put("pump_code","P-E2E-"+UUID.randomUUID().toString().take(6))
            put("pump_number","E2E"); put("station_id",1); put("tank_id",tankId); put("status","active")
        })
        db.insertOrThrow("fuel_sales",null,ContentValues().apply {
            put("uuid",UUID.randomUUID().toString()); put("shift_id",shiftId); put("pump_id",pumpId)
            put("fuel_type_id",1); put("quantity",100.0); put("price_per_liter",1.0)
            put("total_amount",100.0); put("station_id",1); put("status","completed"); put("is_deleted",0)
            put("sale_date","2026-11-19")
        })
        db.insertOrThrow("tank_refills",null,ContentValues().apply {
            put("uuid",UUID.randomUUID().toString()); put("refill_code","REF-E2E-"+UUID.randomUUID().toString().take(6))
            put("tank_id",tankId); put("station_id",1); put("fuel_type_id",1)
            put("delivered_quantity",50.0); put("actual_quantity",50.0)
            put("arrival_date","2026-11-19 10:00:00"); put("is_deleted",0)
        })
        val result=helper.submitShiftReconciliation(
            JSONObject().put("shift_id",shiftId).put("tank_id",tankId).put("actual_liters",950.0),1,actorId
        )
        assertEquals(1000.0,result.getDouble("opening_liters"),0.0001)
        assertEquals(50.0,result.getDouble("deliveries_liters"),0.0001)
        assertEquals(100.0,result.getDouble("sales_liters"),0.0001)
        assertEquals(950.0,result.getDouble("expected_liters"),0.0001)
        assertEquals(0.0,result.getDouble("variance_liters"),0.0001)
        assertTrue(result.getLong("opening_reading_id")>0)
        assertTrue(result.getLong("closing_reading_id")>0)
    }

    @Test
    fun shiftReconciliationRejectsShiftFromAnotherStation() {
        val db = helper.writableDatabase
        val tankId = createLowFuelTank(1, "RECON-SCOPE")
        val foreignShiftId = db.insertOrThrow("shifts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("shift_code", "RECON-SCOPE-" + UUID.randomUUID().toString().take(8))
            put("station_id", 2)
            put("shift_date", "2026-11-19")
            put("shift_type", "morning")
            put("start_time", "2026-11-19 06:00:00")
            put("status", "open")
        })
        val payload = JSONObject().put("shift_id", foreignShiftId).put("tank_id", tankId)
            .put("actual_liters", 250.0)
        assertTrue(runCatching {
            helper.submitShiftReconciliation(payload, 1, actorId)
        }.isFailure)
        val count = db.rawQuery(
            "SELECT COUNT(*) FROM shift_reconciliations WHERE shift_id=? AND tank_id=?",
            arrayOf(foreignShiftId.toString(), tankId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(0, count)
    }

    @Test
    fun approvedShiftReconciliationCannotBeUpdatedDeletedOrReplaced() {
        val db = helper.writableDatabase
        val tankId = createLowFuelTank(1, "RECON-APPROVED-LOCK")
        val shiftId = db.insertOrThrow("shifts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("shift_code", "RECON-APPROVED-" + UUID.randomUUID().toString().take(8))
            put("station_id", 1)
            put("shift_date", "2026-11-20")
            put("shift_type", "morning")
            put("start_time", "2026-11-20 06:00:00")
            put("status", "open")
        })
        prepareReconciliationReadings(tankId, shiftId, 250.0, 250.0)
        val payload = JSONObject().put("shift_id", shiftId).put("tank_id", tankId)
            .put("actual_liters", 250.0).put("tolerance_threshold", 0.01)
        val draft = helper.submitShiftReconciliation(payload, 1, actorId)
        val reconId = draft.getLong("recon_id")
        db.execSQL(
            "UPDATE shift_reconciliations SET approved_by=?, approved_at=? WHERE recon_id=?",
            arrayOf(actorId, "2026-11-20 07:00:00", reconId)
        )
        val auditBefore = db.rawQuery(
            "SELECT COUNT(*) FROM shift_reconciliation_audit WHERE shift_id=? AND tank_id=?",
            arrayOf(shiftId.toString(), tankId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertTrue(runCatching {
            db.update("shift_reconciliations", ContentValues().apply { put("notes", "tampered") },
                "recon_id=?", arrayOf(reconId.toString()))
        }.isFailure)
        assertTrue(runCatching {
            db.delete("shift_reconciliations", "recon_id=?", arrayOf(reconId.toString()))
        }.isFailure)
        prepareReconciliationReadings(tankId, shiftId, 250.0, 240.0)
        val approvedReplacement = JSONObject(payload.toString())
            .put("actual_liters", 240.0)
            .put("cause", "MANUAL_ADJUSTMENT")
            .put("notes", "محاولة استبدال تسوية معتمدة")
        assertTrue(runCatching {
            helper.submitShiftReconciliation(approvedReplacement, 1, actorId)
        }.isFailure)
        val current = db.rawQuery(
            "SELECT approved_by, actual_liters FROM shift_reconciliations WHERE recon_id=?",
            arrayOf(reconId.toString())
        ).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) to c.getDouble(1) }
        assertEquals(actorId, current.first)
        assertEquals(250.0, current.second, 0.0001)
        val auditAfter = db.rawQuery(
            "SELECT COUNT(*) FROM shift_reconciliation_audit WHERE shift_id=? AND tank_id=?",
            arrayOf(shiftId.toString(), tankId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(auditBefore, auditAfter)
    }

    @Test
    fun shiftReconciliationAuditIsAppendOnlyAndCapturesReplacementHistory() {
        val db = helper.writableDatabase
        val tankId = createLowFuelTank(1, "RECON-AUDIT")
        val shiftId = db.insertOrThrow("shifts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("shift_code", "RECON-AUDIT-" + UUID.randomUUID().toString().take(8))
            put("station_id", 1); put("shift_date", "2026-11-19")
            put("shift_type", "morning"); put("start_time", "2026-11-19 06:00:00")
            put("status", "open")
        })
        prepareReconciliationReadings(tankId, shiftId, 250.0, 250.0)
        val payload = JSONObject().put("shift_id", shiftId).put("tank_id", tankId)
            .put("actual_liters", 250.0).put("tolerance_threshold", 0.01)
            .put("cause", "FUEL_SALE").put("notes", "اختبار سجل التدقيق")
        helper.submitShiftReconciliation(payload, 1, actorId)
        prepareReconciliationReadings(tankId, shiftId, 250.0, 240.0)
        helper.submitShiftReconciliation(payload.put("actual_liters", 240.0), 1, actorId)
        val history = helper.getShiftReconciliationAudit(JSONObject().put("shift_id", shiftId).put("tank_id", tankId), 1)
        assertEquals(3, history.length())
        assertEquals("INSERT", history.getJSONObject(0).getString("event_type"))
        assertEquals("DELETE", history.getJSONObject(1).getString("event_type"))
        assertEquals("INSERT", history.getJSONObject(2).getString("event_type"))
        assertTrue(runCatching { db.delete("shift_reconciliation_audit", "shift_id=?", arrayOf(shiftId.toString())) }.isFailure)
        assertTrue(runCatching { db.execSQL("UPDATE shift_reconciliation_audit SET notes='tampered' WHERE shift_id=?", arrayOf(shiftId)) }.isFailure)
        val currentCount = db.rawQuery("SELECT COUNT(*) FROM shift_reconciliations WHERE shift_id=? AND tank_id=?", arrayOf(shiftId.toString(), tankId.toString())).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(1, currentCount)
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
    fun stockLevelsPaginationSlicesRowsAndReportsStableTotals() {
        createProduct(4.0, 1.0)
        createProduct(6.0, 1.0)
        val first = helper.getStockLevelsPage(JSONObject().put("stock_type", "products").put("limit", 1).put("offset", 0), 1)
        val lastOffset = first.getInt("total_count") - 1
        val last = helper.getStockLevelsPage(JSONObject().put("stock_type", "products").put("limit", 1).put("offset", lastOffset), 1)
        assertTrue(first.getInt("total_count") >= 2)
        assertEquals(1, first.getJSONArray("rows").length())
        assertEquals(1, last.getJSONArray("rows").length())
        assertEquals(first.getInt("total_count"), last.getInt("total_count"))
        assertTrue(first.getBoolean("has_next"))
        assertFalse(last.getBoolean("has_next"))
        assertTrue(last.getBoolean("has_previous"))
    }

    @Test
    fun inventoryAgingDoesNotCountUnknownMovementStockInEveryBucket() {
        val productId = createProduct(5.0, 1.0)
        helper.writableDatabase.execSQL(
            "DELETE FROM inventory_movements WHERE product_id=?",
            arrayOf(productId)
        )
        val aging = helper.getInventoryAging(1).getJSONArray("buckets")
        val values = (0 until aging.length()).map { aging.getJSONObject(it).getDouble("value") }
        assertEquals(50.0, values.sum(), 0.0001)
        assertTrue((0 until aging.length()).any { aging.getJSONObject(it).getString("bucket") == "Unknown/No movement" })
    }


    @Test
    fun inventoryAgingAssignsEveryStockRowToExactlyOneBucketAndReconcilesStationValue() {
        val db = helper.writableDatabase
        val today = java.time.LocalDate.parse(DatabaseHelper.getDateOnlyFormat().format(java.util.Date()))
        val quantities = mapOf("0-30" to 2.0, "31-60" to 3.0, "61-90" to 4.0, ">90" to 5.0)
        val expected = linkedMapOf(
            "0-30" to createProduct(0.0, 1.0),
            "31-60" to createProduct(0.0, 1.0),
            "61-90" to createProduct(0.0, 1.0),
            ">90" to createProduct(0.0, 1.0),
            "Unknown/No movement" to createProduct(6.0, 1.0)
        )
        val warehouseId = helper.ensureOperationalWarehouse(1)
        val ages = mapOf("0-30" to 10L, "31-60" to 45L, "61-90" to 75L, ">90" to 120L)

        // Opening balances are inserted directly; seed completed movements explicitly.
        expected.forEach { (bucket, productId) ->
            if (bucket != "Unknown/No movement") {
                helper.addStockMovement(JSONObject()
                    .put("product_id", productId)
                    .put("warehouse_id", warehouseId)
                    .put("movement_type", "in")
                    .put("quantity", quantities.getValue(bucket))
                    .put("unit_cost", 10.0)
                    .put("notes", "DB-05 aging fixture"), 1, actorId)
                val movementDate = today.minusDays(ages.getValue(bucket)).toString()
                assertEquals(1, db.update(
                    "inventory_movements",
                    ContentValues().apply { put("created_at", movementDate) },
                    "product_id=? AND station_id=? AND status='completed' AND is_deleted=0",
                    arrayOf(productId.toString(), "1")
                ))
            }
        }

        // Missing movement history and malformed movement dates both belong only to Unknown.
        val invalidDateProduct = createProduct(0.0, 1.0)
        helper.addStockMovement(JSONObject()
            .put("product_id", invalidDateProduct)
            .put("warehouse_id", warehouseId)
            .put("movement_type", "in")
            .put("quantity", 1.0)
            .put("unit_cost", 10.0)
            .put("notes", "DB-05 invalid-date fixture"), 1, actorId)
        assertEquals(1, db.update(
            "inventory_movements",
            ContentValues().apply { put("created_at", "not-a-date") },
            "product_id=? AND station_id=? AND status='completed' AND is_deleted=0",
            arrayOf(invalidDateProduct.toString(), "1")
        ))

        createProduct(9.0, 1.0, 2, stationTwoActorId)
        val aging = helper.getInventoryAging(1)
        val buckets = aging.getJSONArray("buckets")
        val values = (0 until buckets.length()).associate {
            buckets.getJSONObject(it).getString("bucket") to buckets.getJSONObject(it).getDouble("value")
        }
        assertEquals(setOf("0-30", "31-60", "61-90", ">90", "Unknown/No movement"), values.keys)
        assertEquals(20.0, values.getValue("0-30"), 0.0001)
        assertEquals(30.0, values.getValue("31-60"), 0.0001)
        assertEquals(40.0, values.getValue("61-90"), 0.0001)
        assertEquals(50.0, values.getValue(">90"), 0.0001)
        assertEquals(70.0, values.getValue("Unknown/No movement"), 0.0001)
        val expectedTotal = 210.0
        assertEquals(expectedTotal, aging.getDouble("total_value"), 0.0001)
        assertEquals(expectedTotal, aging.getDouble("bucket_total"), 0.0001)
        assertEquals(0.0, aging.getDouble("reconciliation_difference"), 0.0001)
        assertEquals(today.toString(), aging.getString("business_date"))
    }


    @Test
    fun abcXyzUsesSixMonthCompletedOutboundDemandAndConsumptionValue() {
        val db = helper.writableDatabase
        val month = java.time.YearMonth.parse(helper.currentDateForBridge().take(7))
        val periods = (5 downTo 0).map { month.minusMonths(it.toLong()) }
        val productA = createProduct(500.0, 1.0)
        val productB = createProduct(500.0, 1.0)
        val productC = createProduct(500.0, 1.0)
        val warehouseId = helper.ensureOperationalWarehouse(1)
        val demand = mapOf(
            productA to listOf(10.0, 10.0, 10.0, 10.0, 10.0, 10.0),
            productB to listOf(2.0, 4.0, 6.0, 3.0, 5.0, 5.0),
            productC to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 15.0)
        )
        demand.forEach { (productId, quantities) ->
            quantities.forEachIndexed { index, quantity ->
                if (quantity > 0.0) {
                    val movementId = helper.addStockMovement(
                        JSONObject().put("product_id", productId)
                            .put("warehouse_id", warehouseId)
                            .put("movement_type", "out")
                            .put("quantity", quantity)
                            .put("unit_cost", 10.0)
                            .put("notes", "DB-06 six-month demand fixture"),
                        1, actorId
                    )
                    assertTrue(movementId > 0L)
                    val monthDate = periods[index].atDay(15).toString()
                    assertEquals(1, db.update(
                        "inventory_movements",
                        ContentValues().apply { put("created_at", monthDate) },
                        "id=? AND product_id=? AND station_id=? AND movement_type='out' AND status='completed' AND is_deleted=0",
                        arrayOf(movementId.toString(), productId.toString(), "1")
                    ))
                }
            }
        }
        createProduct(20.0, 1.0, 2, stationTwoActorId)

        val rows = helper.getABCXYZAnalysis(1)
        val byId = (0 until rows.length()).associate {
            val row = rows.getJSONObject(it)
            row.getLong("product_id") to row
        }
        assertEquals("A", byId.getValue(productA).getString("abc"))
        assertEquals("B", byId.getValue(productB).getString("abc"))
        assertEquals("C", byId.getValue(productC).getString("abc"))
        assertEquals("X", byId.getValue(productA).getString("xyz"))
        assertEquals("Y", byId.getValue(productB).getString("xyz"))
        assertEquals("Z", byId.getValue(productC).getString("xyz"))
        assertEquals(600.0, byId.getValue(productA).getDouble("consumption_value"), 0.0001)
        assertEquals(250.0, byId.getValue(productB).getDouble("consumption_value"), 0.0001)
        assertEquals(150.0, byId.getValue(productC).getDouble("consumption_value"), 0.0001)
        assertEquals(1000.0, byId.getValue(productA).getDouble("total_consumption_value"), 0.0001)
        assertEquals(6, byId.getValue(productA).getJSONArray("monthly_demand").length())
    }

    @Test
    fun abcXyzUsesInclusiveCvBoundariesAtPoint25AndPoint50() {
        val db = helper.writableDatabase
        val month = java.time.YearMonth.parse(helper.currentDateForBridge().take(7))
        val periods = (5 downTo 0).map { month.minusMonths(it.toLong()) }
        // Each six-month fixture consumes 60 units; seed sufficient stock so
        // the test exercises CV classification rather than stock-underflow validation.
        val productX = createProduct(100.0, 1.0)
        val productY = createProduct(100.0, 1.0)
        val warehouseId = helper.ensureOperationalWarehouse(1)
        val root3 = kotlin.math.sqrt(3.0)
        val fixtures = listOf(
            productX to listOf(10.0 - 2.5 * root3, 10.0 + 2.5 * root3, 10.0, 10.0, 10.0, 10.0),
            productY to listOf(10.0 - 5.0 * root3, 10.0 + 5.0 * root3, 10.0, 10.0, 10.0, 10.0)
        )
        fixtures.forEach { (productId, quantities) ->
            quantities.forEachIndexed { index, quantity ->
                val movementId = helper.addStockMovement(
                    JSONObject().put("product_id", productId)
                        .put("warehouse_id", warehouseId)
                        .put("movement_type", "out")
                        .put("quantity", quantity)
                        .put("unit_cost", 1.0)
                        .put("notes", "DB-06 CV boundary fixture"),
                    1, actorId
                )
                assertTrue(movementId > 0L)
                assertEquals(1, db.update(
                    "inventory_movements",
                    ContentValues().apply { put("created_at", periods[index].atDay(15).toString()) },
                    "id=? AND product_id=? AND station_id=? AND movement_type='out' AND status='completed' AND is_deleted=0",
                    arrayOf(movementId.toString(), productId.toString(), "1")
                ))
            }
        }
        val rows = helper.getABCXYZAnalysis(1)
        val byId = (0 until rows.length()).associate {
            val row = rows.getJSONObject(it)
            row.getLong("product_id") to row
        }
        assertEquals(0.25, byId.getValue(productX).getDouble("xyz_cv"), 0.00001)
        assertEquals("X", byId.getValue(productX).getString("xyz"))
        assertEquals(0.50, byId.getValue(productY).getDouble("xyz_cv"), 0.00001)
        assertEquals("Y", byId.getValue(productY).getString("xyz"))
    }

    @Test
    fun dryStockKpisUseMovementAgeExpiryWarrantyAndFilteredAbcClassification() {
        val db = helper.writableDatabase
        val today = java.time.LocalDate.parse(helper.currentDateForBridge())
        val oldProduct = createProduct(8.0, 1.0)
        val freshProduct = createProduct(4.0, 1.0)
        val noMovementProduct = createProduct(3.0, 1.0)
        val warehouseId = helper.ensureOperationalWarehouse(1)
        listOf(oldProduct, freshProduct, noMovementProduct).forEach { productId ->
            db.delete("inventory_movements", "product_id=? AND station_id=?", arrayOf(productId.toString(), "1"))
        }
        val oldMovement = helper.addStockMovement(
            JSONObject().put("product_id", oldProduct).put("warehouse_id", warehouseId)
                .put("movement_type", "in").put("quantity", 1.0).put("unit_cost", 10.0)
                .put("notes", "DB-08 stale fixture"), 1, actorId
        )
        assertTrue(oldMovement > 0L)
        db.update("inventory_movements", ContentValues().apply {
            put("created_at", today.minusDays(120).toString())
        }, "id=?", arrayOf(oldMovement.toString()))
        val freshMovement = helper.addStockMovement(
            JSONObject().put("product_id", freshProduct).put("warehouse_id", warehouseId)
                .put("movement_type", "in").put("quantity", 1.0).put("unit_cost", 10.0)
                .put("notes", "DB-08 fresh fixture"), 1, actorId
        )
        assertTrue(freshMovement > 0L)
        db.update("products", ContentValues().apply { put("expiry_date", today.plusDays(10).toString()) },
            "id=?", arrayOf(freshProduct.toString()))
        val soldSerialId = db.insertOrThrow("product_serials", null, ContentValues().apply {
            put("product_id", freshProduct); put("serial_no", "DB08-" + UUID.randomUUID())
            put("status", "SOLD"); put("warranty_end_at", today.plusDays(20).toString())
        })
        assertTrue(soldSerialId > 0L)
        val page = helper.getDryStockPage(JSONObject().put("search", "Inventory screen test"), 1)
        val kpis = page.getJSONObject("kpis")
        assertTrue(kpis.getDouble("dead_stock_value") >= 80.0)
        assertTrue(kpis.getDouble("unknown_age_value") >= 30.0)
        assertTrue(kpis.getDouble("expiring_soon_stock_value") >= 40.0)
        assertEquals(1, kpis.getInt("active_warranty_count"))
        assertEquals(1, kpis.getInt("warranty_expiring_soon_count"))
        assertEquals(3, kpis.getInt("abc_a_count") + kpis.getInt("abc_b_count") + kpis.getInt("abc_c_count"))
        assertTrue(page.has("abc_xyz"))
        assertTrue(kpis.getDouble("aging_over_90_value") >= 80.0)
        assertTrue(kpis.getDouble("aging_unknown_value") >= 30.0)
        assertTrue(kpis.has("aging_reconciliation_difference"))
        assertTrue(noMovementProduct > 0L)
    }

    @Test
    fun dryStockPageAppliesSearchAndCategoryFilters() {
        val productId = createProduct(7.0, 2.0)
        val db = helper.writableDatabase
        val categoryId = db.rawQuery("SELECT category_id FROM products WHERE id=?", arrayOf(productId.toString())).use {
            check(it.moveToFirst()); it.getLong(0)
        }
        db.update("products", ContentValues().apply {
            put("product_name", "DRY-FILTER-UNIQUE")
            put("product_name_ar", "صنف اختبار المرشحات")
        }, "id=?", arrayOf(productId.toString()))

        val page = helper.getDryStockPage(
            JSONObject().put("search", "DRY-FILTER-UNIQUE").put("category_id", categoryId),
            1
        )
        val rows = page.getJSONArray("products")
        assertEquals(1, rows.length())
        assertEquals(productId, rows.getJSONObject(0).getLong("product_id"))
        assertEquals(70.0, page.getJSONObject("kpis").getDouble("inventory_value"), 0.0001)
    }

    @Test
    fun dryStockPageAppliesLocationAndExpiryFiltersTogether() {
        val db = helper.writableDatabase
        val warehouseId = helper.ensureOperationalWarehouse(1)
        val locationId = db.insertOrThrow("stock_locations", null, ContentValues().apply {
            put("warehouse_id", warehouseId)
            put("name", "DB19 filter location")
            put("type", "warehouse")
            put("is_active", 1)
        })
        val otherLocationId = db.insertOrThrow("stock_locations", null, ContentValues().apply {
            put("warehouse_id", warehouseId)
            put("name", "DB19 other location")
            put("type", "warehouse")
            put("is_active", 1)
        })
        val today = java.time.LocalDate.parse(helper.currentDateForBridge())
        val soonProduct = createProduct(5.0, 1.0)
        val otherLocationProduct = createProduct(6.0, 1.0)
        val activeExpiryProduct = createProduct(7.0, 1.0)
        fun addBatch(productId: Long, location: Long, expiry: String) {
            db.insertOrThrow("product_batches", null, ContentValues().apply {
                put("product_id", productId)
                put("lot_number", "DB19-" + UUID.randomUUID().toString().take(8))
                put("expiry_date", expiry)
                put("quantity", 1.0)
                put("location_id", location)
                put("received_at", today.toString())
            })
        }
        addBatch(soonProduct, locationId, today.plusDays(10).toString())
        addBatch(otherLocationProduct, otherLocationId, today.plusDays(10).toString())
        addBatch(activeExpiryProduct, locationId, today.plusDays(60).toString())

        val page = helper.getDryStockPage(
            JSONObject().put("location_id", locationId).put("expiry_status", "soon"),
            1
        )
        val ids = (0 until page.getJSONArray("products").length()).map {
            page.getJSONArray("products").getJSONObject(it).getLong("product_id")
        }
        assertEquals(listOf(soonProduct), ids)
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
    fun expiringBatchAlertsAreStationScopedDeduplicatedAndResolvedWhenBatchIsEmpty() {
        val db = helper.writableDatabase
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Aden"))
        val stationOneProduct = createProduct(10.0, 1.0)
        val stationTwoProduct = createProduct(10.0, 1.0, 2, stationTwoActorId)
        val expiringBatchId = db.insertOrThrow("product_batches", null, ContentValues().apply {
            put("product_id", stationOneProduct)
            put("lot_number", "EXP-" + UUID.randomUUID().toString().take(8))
            put("expiry_date", today.plusDays(5).toString())
            put("quantity", 4.0)
            put("received_at", today.minusDays(10).toString())
        })
        db.insertOrThrow("product_batches", null, ContentValues().apply {
            put("product_id", stationOneProduct)
            put("lot_number", "FUTURE-" + UUID.randomUUID().toString().take(8))
            put("expiry_date", today.plusDays(60).toString())
            put("quantity", 3.0)
            put("received_at", today.minusDays(2).toString())
        })
        db.insertOrThrow("product_batches", null, ContentValues().apply {
            put("product_id", stationTwoProduct)
            put("lot_number", "OTHER-" + UUID.randomUUID().toString().take(8))
            put("expiry_date", today.plusDays(3).toString())
            put("quantity", 2.0)
            put("received_at", today.minusDays(2).toString())
        })

        fun expiryRows(stationId: Int) = helper.getStockAlertRecordsContract(
            JSONObject().put("limit", 0).put("status", "expiry"),
            stationId
        ).getJSONArray("rows")

        val first = expiryRows(1)
        val firstBatchAlerts = (0 until first.length()).map { first.getJSONObject(it) }
            .filter { runCatching { JSONObject(it.optString("extra_data")).optLong("batch_id") }.getOrDefault(0L) == expiringBatchId }
        assertEquals(1, firstBatchAlerts.size)
        assertEquals(stationOneProduct, firstBatchAlerts.single().getLong("product_id"))
        assertEquals("critical", firstBatchAlerts.single().getString("alert_level"))
        assertEquals(0, firstBatchAlerts.single().getInt("is_resolved"))

        val second = expiryRows(1)
        val secondBatchAlerts = (0 until second.length()).count {
            runCatching { JSONObject(second.getJSONObject(it).optString("extra_data")).optLong("batch_id") }.getOrDefault(0L) == expiringBatchId
        }
        assertEquals("Repeated synchronization must not duplicate a batch alert", 1, secondBatchAlerts)

        val stationTwoRows = expiryRows(2)
        assertTrue((0 until stationTwoRows.length()).any {
            runCatching { JSONObject(stationTwoRows.getJSONObject(it).optString("extra_data")).optLong("batch_id") }.getOrDefault(0L) > 0L
        })
        assertTrue((0 until stationTwoRows.length()).none {
            runCatching { JSONObject(stationTwoRows.getJSONObject(it).optString("extra_data")).optLong("batch_id") }.getOrDefault(0L) == expiringBatchId
        })

        assertEquals(1, db.update("product_batches", ContentValues().apply { put("quantity", 0.0) },
            "batch_id=?", arrayOf(expiringBatchId.toString())))
        val afterReceipt = expiryRows(1)
        val resolved = (0 until afterReceipt.length()).map { afterReceipt.getJSONObject(it) }
            .first { runCatching { JSONObject(it.optString("extra_data")).optLong("batch_id") }.getOrDefault(0L) == expiringBatchId }
        assertEquals("An empty batch must resolve its prior expiry alert", 1, resolved.getInt("is_resolved"))
    }


    @Test
    fun unifiedAlertsIncludeWarrantyAnomaliesAndLatePurchaseOrdersWithSharedFilters() {
        val db=helper.writableDatabase; val today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Aden"))
        val p1=createProduct(10.0,1.0); val p2=createProduct(10.0,1.0,2,stationTwoActorId)
        val warrantyId=db.insertOrThrow("product_serials",null,ContentValues().apply {
            put("product_id",p1); put("serial_no","WARRANTY-"+UUID.randomUUID()); put("status","SOLD"); put("warranty_end_at",today.plusDays(4).toString())
        })
        db.insertOrThrow("product_serials",null,ContentValues().apply {
            put("product_id",p2); put("serial_no","OTHER-WARRANTY-"+UUID.randomUUID()); put("status","AVAILABLE"); put("warranty_end_at",today.plusDays(3).toString())
        })
        db.insertOrThrow("product_serials",null,ContentValues().apply {
            put("product_id",p1); put("serial_no","RETURNED-WARRANTY-"+UUID.randomUUID()); put("status","RETURNED"); put("warranty_end_at",today.plusDays(2).toString())
        })
        val anomalyId=db.insertOrThrow("inventory_anomalies",null,ContentValues().apply {
            put("station_id",1); put("item_type","PRODUCT"); put("item_id",p1); put("anomaly_type","COUNT_VARIANCE")
            put("severity","critical"); put("metric_value",8.0); put("threshold_value",2.0); put("status","OPEN")
            put("details","اختلاف جرد تجريبي"); put("detected_at",today.toString())
        })
        db.insertOrThrow("inventory_anomalies",null,ContentValues().apply {
            put("station_id",2); put("item_type","PRODUCT"); put("item_id",p2); put("anomaly_type","COUNT_VARIANCE")
            put("severity","critical"); put("status","OPEN"); put("detected_at",today.toString())
        })
        val poId=db.insertOrThrow("purchase_orders",null,ContentValues().apply {
            put("po_number","PO-ALERT-"+UUID.randomUUID().toString().take(8)); put("station_id",1); put("status","approved")
            put("expected_at",today.minusDays(5).toString()); put("total_amount",1250.0)
        })
        db.insertOrThrow("purchase_orders",null,ContentValues().apply {
            put("po_number","PO-OTHER-"+UUID.randomUUID().toString().take(8)); put("station_id",2); put("status","partial")
            put("expected_at",today.minusDays(3).toString()); put("total_amount",500.0)
        })
        db.insertOrThrow("purchase_orders",null,ContentValues().apply {
            put("po_number","PO-RECEIVED-"+UUID.randomUUID().toString().take(8)); put("station_id",1); put("status","received")
            put("expected_at",today.minusDays(10).toString()); put("total_amount",700.0)
        })
        fun rows(type:String,station:Int=1)=helper.getStockAlertRecordsContract(JSONObject().put("alert_type",type).put("limit",0),station).getJSONArray("rows")
        val warranties=rows("warranty"); assertEquals(1,warranties.length()); assertEquals(warrantyId,warranties.getJSONObject(0).getLong("serial_id"))
        assertEquals("critical",warranties.getJSONObject(0).getString("alert_level"))
        val anomalies=rows("anomaly"); assertEquals(1,anomalies.length())
        assertEquals(anomalyId,JSONObject(anomalies.getJSONObject(0).getString("extra_data")).getLong("anomaly_id"))
        val late=rows("po_late"); assertEquals(1,late.length()); assertEquals(poId,late.getJSONObject(0).getLong("purchase_order_id"))
        assertEquals(5,late.getJSONObject(0).getInt("days_overdue"))
        assertEquals(1,rows("warranty",2).length()); assertTrue(rows("warranty",2).getJSONObject(0).getLong("serial_id")!=warrantyId)
        assertEquals(1,rows("anomaly",2).length()); assertEquals(1,rows("po_late",2).length())
        val all=helper.getStockAlertRecordsContract(JSONObject().put("limit",0),1); val stats=all.getJSONObject("statistics")
        assertEquals(1,stats.getInt("warranty_count")); assertEquals(1,stats.getInt("anomaly_count")); assertEquals(1,stats.getInt("po_late_count"))
        assertEquals(all.getInt("total_count"),stats.getInt("total_count"))
        val page=helper.getStockAlertRecordsContract(JSONObject().put("limit",1).put("offset",0),1)
        assertEquals(1,page.getJSONArray("rows").length()); assertTrue(page.getBoolean("has_next"))
        assertEquals(all.getInt("total_count"),page.getInt("total_count"))
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


    @Test
    fun v66UpgradeRepairsMissingInventorySchemasBeforeScreenQueriesRun() {
        // Reproduce an already-installed V66 database from an APK that did not
        // ship the Phase 1-4 tables. Keep all legacy business tables and rows intact.
        val db = helper.writableDatabase
        listOf(
            "purchase_order_lines", "purchase_orders", "events_outbox",
            "inventory_forecasts", "inventory_anomalies", "inventory_health_snapshots",
            "fuel_temperature_coefficients", "role_scopes",
            "product_serials", "product_batches", "stock_locations",
            "shift_reconciliations", "tank_dip_readings", "tank_readings",
            "inventory_reason_codes"
        ).forEach { table -> db.execSQL("DROP TABLE IF EXISTS $table") }

        DatabaseHelper.closeInstance()
        val installedDb = SQLiteDatabase.openDatabase(
            context.getDatabasePath(DatabaseHelper.DATABASE_NAME).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        try {
            installedDb.version = 66
        } finally {
            installedDb.close()
        }

        // Opening through the real SQLiteOpenHelper must run V66 -> V67.
        helper = DatabaseHelper.getInstance(context)
        val upgraded = helper.writableDatabase
        val requiredTables = listOf(
            "tank_readings", "inventory_reason_codes", "tank_dip_readings",
            "shift_reconciliations", "stock_locations", "product_batches",
            "product_serials", "purchase_orders", "purchase_order_lines",
            "events_outbox", "inventory_forecasts", "inventory_anomalies",
            "inventory_health_snapshots", "fuel_temperature_coefficients", "role_scopes"
        )
        requiredTables.forEach { table ->
            val exists = upgraded.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table)
            ).use { it.moveToFirst() }
            assertTrue("V67 migration must create $table", exists)
        }

        val productId = createProduct(8.0, 2.0)
        val tankId = createLowFuelTank(1, "V67")
        val dryStock = helper.getDryStockPage(JSONObject(), 1)
        assertTrue((0 until dryStock.getJSONArray("products").length()).any {
            dryStock.getJSONArray("products").getJSONObject(it).optLong("product_id") == productId
        })
        val wetStock = helper.getWetStockPage(1)
        assertTrue((0 until wetStock.getJSONArray("tanks").length()).any {
            wetStock.getJSONArray("tanks").getJSONObject(it).optLong("tank_id") == tankId
        })

        // These are the exact read paths that previously failed with SQLITE_ERROR.
        helper.runInventoryAnomalyDetection(1)
        helper.getForecourtSnapshot(1)
        assertTrue(upgraded.rawQuery("PRAGMA integrity_check", null).use {
            it.moveToFirst() && it.getString(0) == "ok"
        })
        assertTrue(upgraded.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() })
    }

    @Test
    fun warrantyQueryIncludesSoldSerialsAndExcludesReturnedSerials() {
        val productId=createProduct(3.0,1.0); val db=helper.writableDatabase
        val sold=db.insertOrThrow("product_serials",null,ContentValues().apply { put("product_id",productId); put("serial_no","SOLD-"+UUID.randomUUID()); put("status","SOLD"); put("warranty_end_at","2099-12-31") })
        val returned=db.insertOrThrow("product_serials",null,ContentValues().apply { put("product_id",productId); put("serial_no","RETURNED-"+UUID.randomUUID()); put("status","RETURNED"); put("warranty_end_at","2099-12-31") })
        val rows=helper.getExpiringWarranties(36500,1)
        assertTrue((0 until rows.length()).any { rows.getJSONObject(it).getLong("serial_id")==sold })
        assertFalse((0 until rows.length()).any { rows.getJSONObject(it).getLong("serial_id")==returned })
    }

    @Test
    fun healthScorePersistsAllFourV3ComponentsAndUsesExactWeights() {
        val db = helper.writableDatabase
        val productId = createProduct(1.0, 5.0)
        db.insertOrThrow("inventory_movements", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("movement_code", "HS-"+UUID.randomUUID().toString().take(8))
            put("product_id", productId); put("station_id", 1); put("movement_type", "out")
            put("quantity_before", 1.0); put("quantity_change", 1.0); put("quantity_after", 0.0)
            put("performed_by", actorId); put("status", "completed"); put("is_deleted", 0)
            put("created_at", "2000-01-01 00:00:00")
        })
        val score = helper.calculateInventoryHealthScore(1)
        assertTrue(score in 0.0..100.0)
        // One active tangible product is both below reorder level and has no
        // outbound demand in the last 90 days: 30 + 20 weighted-point penalties.
        assertEquals(50.0, score, 0.0001)
        val snapshot = db.rawQuery(
            "SELECT health_score,low_stock_ratio,dead_stock_ratio,variance_avg_pct,expiring_soon_ratio FROM inventory_health_snapshots WHERE station_id=1 ORDER BY snapshot_id DESC LIMIT 1",
            null
        ).use { c -> check(c.moveToFirst()); (0..4).map { c.getDouble(it) } }
        assertEquals(score, snapshot[0], 0.0001)
        assertTrue(snapshot[1] >= 0.0 && snapshot[1] <= 1.0)
        assertTrue(snapshot[2] >= 0.0 && snapshot[2] <= 1.0)
        assertTrue(snapshot[4] >= 0.0 && snapshot[4] <= 1.0)
    }

    @Test
    fun vcfRejectsInvalidInputsAndUsesConfiguredFuelTypeCoefficient() {
        val db = helper.writableDatabase
        db.insertOrThrow("fuel_temperature_coefficients", null, ContentValues().apply {
            put("fuel_type_id", 987654L)
            put("coefficient_exp", 0.001)
        })
        val standard = helper.calculateVcf(987654L, 15.0, 1)
        assertEquals(1.0, standard.getDouble("vcf"), 0.0000001)
        assertEquals(0.001, standard.getDouble("coefficient_exp"), 0.0000001)
        assertEquals(15.0, standard.getDouble("reference_temperature_c"), 0.0001)
        val warm = helper.calculateVcf(987654L, 35.0, 1)
        assertTrue(warm.getDouble("vcf") < 1.0)
        assertTrue(runCatching { helper.calculateVcf(0L, 20.0, 1) }.isFailure)
        assertTrue(runCatching { helper.calculateVcf(987654L, Double.NaN, 1) }.isFailure)
        assertTrue(runCatching { helper.calculateVcf(987654L, 20.0, 0) }.isFailure)
    }

    @Test
    fun anomalyDetectionDoesNotInventMissingWaterAndDeduplicatesAcknowledgedAlerts() {
        val db = helper.writableDatabase
        val tankId = createLowFuelTank(1, "ANOMALY")
        val first = helper.runInventoryAnomalyDetection(1)
        val lowFuel = (0 until first.length()).map { first.getJSONObject(it) }
            .filter { it.optString("type") == "LOW_FUEL" }
        assertEquals(1, lowFuel.size)
        val anomalyId = lowFuel.single().getLong("anomaly_id")
        db.update("inventory_anomalies", ContentValues().apply { put("status", "ACKNOWLEDGED") },
            "anomaly_id=?", arrayOf(anomalyId.toString()))
        val second = helper.runInventoryAnomalyDetection(1)
        assertEquals(0, (0 until second.length()).count { second.getJSONObject(it).optString("type") == "LOW_FUEL" })
        val waterCount = db.rawQuery(
            "SELECT COUNT(*) FROM inventory_anomalies WHERE station_id=1 AND item_id=? AND anomaly_type='WATER_LEVEL'",
            arrayOf(tankId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(0, waterCount)
    }

    @Test
    fun verifiedPumpMeterVarianceIsDetectedAndDeduplicated() {
        val db = helper.writableDatabase
        val tankId = createLowFuelTank(1, "PUMP-VARIANCE")
        val pumpId = db.insertOrThrow("pumps", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("pump_code", "PV-" + UUID.randomUUID().toString().take(8))
            put("pump_number", "PV1")
            put("station_id", 1)
            put("tank_id", tankId)
            put("status", "active")
        })
        val nozzleId = db.insertOrThrow("pump_nozzles", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("nozzle_code", "NV-" + UUID.randomUUID().toString().take(8))
            put("nozzle_number", "1")
            put("pump_id", pumpId)
            put("fuel_type_id", 1)
            put("status", "active")
        })
        db.insertOrThrow("meter_readings", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("reading_code", "MR-" + UUID.randomUUID().toString().take(8))
            put("pump_id", pumpId); put("nozzle_id", nozzleId); put("station_id", 1)
            put("reading_date", helper.currentDateForBridge())
            put("opening_reading", 1000.0); put("closing_reading", 1200.0)
            put("sold_liters", 200.0); put("system_sold_liters", 180.0)
            put("difference", 20.0); put("tolerance_limit", 0.5)
            put("read_by", actorId); put("status", "verified")
        })
        val first = helper.runInventoryAnomalyDetection(1)
        val found = (0 until first.length()).map { first.getJSONObject(it) }
            .filter { it.optString("type") == "PUMP_VARIANCE" }
        assertEquals(1, found.size)
        // The public JSON contract intentionally exposes anomaly_id/type/item_type;
        // verify the scoped entity identity against the persisted ledger row instead.
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM inventory_anomalies WHERE anomaly_id=? AND station_id=1 AND item_type='FUEL' AND item_id=? AND anomaly_type='PUMP_VARIANCE'",
            arrayOf(found.single().getLong("anomaly_id").toString(), nozzleId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        val second = helper.runInventoryAnomalyDetection(1)
        assertEquals(0, (0 until second.length()).count {
            second.getJSONObject(it).optString("type") == "PUMP_VARIANCE"
        })
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM inventory_anomalies WHERE station_id=1 AND item_id=? AND anomaly_type='PUMP_VARIANCE' AND status IN ('OPEN','ACKNOWLEDGED')",
            arrayOf(nozzleId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) })
    }

    @Test
    fun demandForecastPersistsDailyProductForecastWithXyzMethod() {
        val db = helper.writableDatabase
        val productId = createProduct(12.0, 2.0)
        db.update("products", ContentValues().apply { put("xyz_class", "Z") }, "id=?", arrayOf(productId.toString()))
        val today = java.time.LocalDate.parse(helper.currentDateForBridge())
        repeat(8) { index ->
            val date = today.minusDays((index * 7 + 1).toLong())
            db.insertOrThrow("inventory_movements", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString()); put("movement_code", "FC-"+UUID.randomUUID().toString().take(8))
                put("product_id", productId); put("station_id", 1); put("movement_type", "out")
                put("quantity_before", 20.0); put("quantity_change", 2.0); put("quantity_after", 18.0)
                put("performed_by", actorId); put("status", "completed"); put("is_deleted", 0)
                put("created_at", date.toString()+" 12:00:00")
            })
        }
        val forecast = helper.getDemandForecast(1)
        val item = (0 until forecast.length()).map { forecast.getJSONObject(it) }
            .firstOrNull { it.getLong("item_id") == productId }
        assertTrue(item != null)
        assertEquals("LOCAL_56D_WEEKDAY_XYZ", item!!.getString("method"))
        assertTrue(item.getDouble("forecast_7d") > 0.0)
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM inventory_forecasts WHERE station_id=1 AND item_type='PRODUCT' AND item_id=?",
            arrayOf(productId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) })
    }

    @Test
    fun anomalyDetectionAddsStationScopedProductLowAndDeadStockRulesAndResolvesThem() {
        val db = helper.writableDatabase
        val productId = createProduct(2.0, 5.0)
        val oldDate = java.time.LocalDate.parse(helper.currentDateForBridge()).minusDays(120).toString()
        db.update("products", ContentValues().apply { put("created_at", "$oldDate 08:00:00") },
            "id=? AND station_id=1", arrayOf(productId.toString()))
        db.update("inventory_movements", ContentValues().apply { put("created_at", "$oldDate 08:00:00") },
            "product_id=? AND station_id=1", arrayOf(productId.toString()))

        val first = helper.runInventoryAnomalyDetection(1)
        val firstTypes = (0 until first.length()).map { first.getJSONObject(it).optString("type") }
        assertTrue(firstTypes.contains("PRODUCT_LOW_STOCK"))
        assertTrue(firstTypes.contains("PRODUCT_DEAD_STOCK"))
        val second = helper.runInventoryAnomalyDetection(1)
        assertEquals(0, (0 until second.length()).count {
            second.getJSONObject(it).optString("type") in setOf("PRODUCT_LOW_STOCK", "PRODUCT_DEAD_STOCK")
        })
        val activeBeforeRestore = db.rawQuery(
            "SELECT COUNT(*) FROM inventory_anomalies WHERE station_id=1 AND item_id=? AND item_type='PRODUCT' AND status IN ('OPEN','ACKNOWLEDGED')",
            arrayOf(productId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(2, activeBeforeRestore)

        db.update("inventory_levels", ContentValues().apply { put("quantity_on_hand", 1000.0) },
            "product_id=?", arrayOf(productId.toString()))
        db.update("products", ContentValues().apply { put("quantity", 1000.0) },
            "id=? AND station_id=1", arrayOf(productId.toString()))
        db.insertOrThrow("inventory_movements", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("movement_code", "RESTOCK-TEST-" + UUID.randomUUID().toString().take(8))
            put("product_id", productId); put("station_id", 1); put("movement_type", "out")
            put("quantity_before", 1000.0); put("quantity_change", -1.0); put("quantity_after", 999.0)
            put("performed_by", actorId); put("status", "completed"); put("is_deleted", 0)
            put("created_at", helper.currentDateForBridge() + " 12:00:00")
        })
        helper.runInventoryAnomalyDetection(1)
        val activeAfterRestore = db.rawQuery(
            "SELECT COUNT(*) FROM inventory_anomalies WHERE station_id=1 AND item_id=? AND item_type='PRODUCT' AND status IN ('OPEN','ACKNOWLEDGED')",
            arrayOf(productId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(0, activeAfterRestore)
        val historical = db.rawQuery(
            "SELECT COUNT(*) FROM inventory_anomalies WHERE station_id=1 AND item_id=? AND item_type='PRODUCT' AND status='RESOLVED'",
            arrayOf(productId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(2, historical)
    }

    @Test
    fun demandForecastIncludesZeroDemandProductsAndPersistsIdempotently() {
        val db = helper.writableDatabase
        val zeroDemandProduct = createProduct(20.0, 2.0)
        val first = helper.getDemandForecast(1)
        val zeroItem = (0 until first.length()).map { first.getJSONObject(it) }
            .firstOrNull { it.optString("item_type") == "PRODUCT" && it.optLong("item_id") == zeroDemandProduct }
        assertTrue("Active products with no outbound history must still receive an explicit zero forecast", zeroItem != null)
        assertEquals(0.0, zeroItem!!.getDouble("forecast_7d"), 0.0001)
        assertTrue(zeroItem.isNull("days_of_cover"))
        helper.getDemandForecast(1)
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM inventory_forecasts WHERE station_id=1 AND item_type='PRODUCT' AND item_id=?",
            arrayOf(zeroDemandProduct.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) })
    }

    @Test
    fun demandForecastPersistsStationScopedFuelForecastFromCompletedFuelSales() {
        val db = helper.writableDatabase
        val tankId = createLowFuelTank(1, "FUEL-FORECAST")
        val today = java.time.LocalDate.parse(helper.currentDateForBridge())
        repeat(8) { index ->
            val saleDay = today.minusDays((index * 7 + 1).toLong())
            db.insertOrThrow("fuel_sales", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("station_id", 1)
                put("fuel_type_id", 1)
                put("quantity", 40.0)
                put("price_per_liter", 1.0)
                put("total_amount", 40.0)
                put("sale_date", saleDay.toString())
                put("business_day", saleDay.toString())
                put("status", "completed")
                put("is_deleted", 0)
                put("created_at", "$saleDay 12:00:00")
            })
        }
        val forecasts = helper.getDemandForecast(1)
        val fuel = (0 until forecasts.length()).map { forecasts.getJSONObject(it) }
            .firstOrNull { it.optString("item_type") == "FUEL" && it.optLong("item_id") == 1L }
        assertTrue(fuel != null)
        assertEquals("LOCAL_56D_WEEKDAY_FUEL", fuel!!.getString("method"))
        assertTrue(fuel.getDouble("forecast_7d") > 0.0)
        assertTrue(fuel.getDouble("days_of_cover") > 0.0)
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM inventory_forecasts WHERE station_id=1 AND item_type='FUEL' AND item_id=1",
            null
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        assertEquals(0, db.rawQuery(
            "SELECT COUNT(*) FROM inventory_forecasts WHERE station_id=2 AND item_type='FUEL' AND item_id=1",
            null
        ).use { c -> c.moveToFirst(); c.getInt(0) })
    }

    @Test
    fun outboxUsesStationScopedIdempotencyClaimAndAcknowledgement() {
        val event=JSONObject().put("event_type","pos_sale_completed").put("consumer","stock-levels").put("idempotency_key","acceptance-sale-1").put("payload",JSONObject().put("sale_id",1001))
        val id=helper.publishEvent(event,1); assertEquals(id,helper.publishEvent(event,1))
        val claimed=helper.claimPendingEvents(JSONObject(),1); assertEquals(1,claimed.length())
        assertEquals("PROCESSING",claimed.getJSONObject(0).getString("status")); assertEquals(1,claimed.getJSONObject(0).getInt("attempts"))
        assertFalse(helper.acknowledgeEvent(id,2,"stock-levels"))
        assertTrue(helper.acknowledgeEvent(id,1,"stock-levels")); assertFalse(helper.acknowledgeEvent(id,1,"stock-levels"))
        val status=helper.writableDatabase.rawQuery("SELECT status FROM events_outbox WHERE event_id=?",arrayOf(id.toString())).use { c -> check(c.moveToFirst()); c.getString(0) }
        assertEquals("DELIVERED",status)
    }

    @Test
    fun outboxFailureReturnsClaimedEventToRetryQueue() {
        val id=helper.publishEvent(JSONObject().put("event_type","fuel_sale_completed"),1)
        assertEquals(1,helper.claimPendingEvents(JSONObject(),1).length())
        assertTrue(helper.failEvent(id,1,"stock-levels","temporary failure"))
        val row=helper.writableDatabase.rawQuery("SELECT status,attempts,lease_until FROM events_outbox WHERE event_id=?",arrayOf(id.toString())).use { c -> check(c.moveToFirst()); Triple(c.getString(0),c.getInt(1),c.getString(2)) }
        assertEquals("PENDING",row.first); assertEquals(1,row.second); assertTrue(row.third==null)
    }

    @Test
    fun outboxExpiredProcessingLeaseIsRecoveredAfterWorkerCrash() {
        val id=helper.publishEvent(JSONObject().put("event_type","pos_sale_completed"),1)
        assertEquals(1,helper.claimPendingEvents(JSONObject(),1).length())
        helper.writableDatabase.execSQL("UPDATE events_outbox SET lease_until='2000-01-01 00:00:00' WHERE event_id=?",arrayOf(id))
        val recovered=helper.claimPendingEvents(JSONObject(),1)
        assertEquals(1,recovered.length())
        assertEquals(id,recovered.getJSONObject(0).getLong("event_id"))
        assertEquals(2,recovered.getJSONObject(0).getInt("attempts"))
        assertEquals("PROCESSING",recovered.getJSONObject(0).getString("status"))
    }


    @Test
    fun trackedBatchSaleEnforcesFEFOAndNeverSelectsExpiredStock() {
        val db = helper.writableDatabase
        val productId = createProduct(0.0, 0.0)
        db.update("products", ContentValues().apply { put("track_batch", 1) }, "id=?", arrayOf(productId.toString()))
        val today = java.time.LocalDate.parse(helper.currentDateForBridge())
        fun batch(expiry: String, quantity: Double): Long = db.insertOrThrow("product_batches", null, ContentValues().apply {
            put("product_id", productId); put("lot_number", "FEFO-" + UUID.randomUUID())
            put("expiry_date", expiry); put("quantity", quantity); put("received_at", today.toString())
        })
        batch(today.minusDays(1).toString(), 10.0) // expired stock must be skipped
        val earliestValid = batch(today.plusDays(3).toString(), 5.0)
        val laterValid = batch(today.plusDays(12).toString(), 8.0)
        val resolve = DatabaseHelper::class.java.getDeclaredMethod(
            "resolveDryStockSelection", SQLiteDatabase::class.java, JSONObject::class.java
        ).apply { isAccessible = true }
        val item = JSONObject().put("product_id", productId).put("quantity", 2.0).put("track_batch", 1)
        resolve.invoke(helper, db, item)
        assertEquals(earliestValid, item.getLong("batch_id"))
        val consume = DatabaseHelper::class.java.getDeclaredMethod(
            "consumeDryStockSelection", SQLiteDatabase::class.java, JSONObject::class.java, Long::class.javaPrimitiveType!!
        ).apply { isAccessible = true }
        consume.invoke(helper, db, item, 991L)
        assertEquals(3.0, db.rawQuery("SELECT quantity FROM product_batches WHERE batch_id=?", arrayOf(earliestValid.toString())).use { it.moveToFirst(); it.getDouble(0) }, 0.0001)
        assertEquals(8.0, db.rawQuery("SELECT quantity FROM product_batches WHERE batch_id=?", arrayOf(laterValid.toString())).use { it.moveToFirst(); it.getDouble(0) }, 0.0001)
        val bypass = JSONObject().put("product_id", productId).put("quantity", 1.0)
            .put("track_batch", 1).put("batch_id", laterValid)
        assertTrue(runCatching { resolve.invoke(helper, db, bypass) }.isFailure)
    }

    @Test
    fun inventoryThresholdCreatesOneStationScopedDraftPurchaseOrderImmediately() {
        val db = helper.writableDatabase
        val productId = createProduct(10.0, 0.0)
        db.update("products", ContentValues().apply {
            put("reorder_point", 5.0); put("safety_stock", 3.0)
        }, "id=? AND station_id=1", arrayOf(productId.toString()))
        helper.adjustProductStock(productId, 4.0, 1, actorId)
        val poId = db.rawQuery(
            "SELECT po.po_id FROM purchase_orders po JOIN purchase_order_lines l ON l.po_id=po.po_id WHERE po.station_id=1 AND l.item_type='PRODUCT' AND l.item_id=? AND po.status='draft' AND l.notes LIKE 'AUTO_REORDER:%'",
            arrayOf(productId.toString())
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }
        val quantity = db.rawQuery("SELECT quantity FROM purchase_order_lines WHERE po_id=?", arrayOf(poId.toString())).use { c -> check(c.moveToFirst()); c.getDouble(0) }
        assertEquals(4.0, quantity, 0.0001)
        helper.adjustProductStock(productId, 3.0, 1, actorId)
        val count = db.rawQuery(
            "SELECT COUNT(*) FROM purchase_orders po JOIN purchase_order_lines l ON l.po_id=po.po_id WHERE po.station_id=1 AND l.item_type='PRODUCT' AND l.item_id=? AND po.status IN ('draft','submitted','approved','partial') AND l.notes LIKE 'AUTO_REORDER:%'",
            arrayOf(productId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals("Only one active automatic PO may exist for a station/product", 1, count)
        assertEquals(0, db.rawQuery(
            "SELECT COUNT(*) FROM purchase_orders po JOIN purchase_order_lines l ON l.po_id=po.po_id WHERE po.station_id=2 AND l.item_type='PRODUCT' AND l.item_id=?",
            arrayOf(productId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) })
    }

    @Test
    fun wetStockToleranceBoundaryRequiresReasonOnlyAboveOneHundredthLiter() {
        val db = helper.writableDatabase
        fun scenario(suffix: String, actual: Double, withReason: Boolean): JSONObject {
            val tankId = createLowFuelTank(1, suffix)
            val shiftId = db.insertOrThrow("shifts", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString()); put("shift_code", "TOL-$suffix-" + UUID.randomUUID().toString().take(6))
                put("station_id", 1); put("shift_date", "2026-11-19"); put("shift_type", "morning")
                put("start_time", "2026-11-19 06:00:00"); put("status", "open")
            })
            prepareReconciliationReadings(tankId, shiftId, 1000.0, actual)
            val payload = JSONObject().put("shift_id", shiftId).put("tank_id", tankId)
                .put("tolerance_threshold", 0.01)
            if (withReason) payload.put("cause", "FUEL_SALE").put("notes", "فرق موثق لاختبار حد التسامح")
            return helper.submitShiftReconciliation(payload, 1, actorId)
        }
        val within = scenario("TOL-WITHIN", 1000.009, false)
        assertEquals(0.009, within.getDouble("variance_liters"), 0.000001)
        val tankId = createLowFuelTank(1, "TOL-OVER")
        val shiftId = db.insertOrThrow("shifts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("shift_code", "TOL-OVER-" + UUID.randomUUID().toString().take(6))
            put("station_id", 1); put("shift_date", "2026-11-19"); put("shift_type", "morning")
            put("start_time", "2026-11-19 06:00:00"); put("status", "open")
        })
        prepareReconciliationReadings(tankId, shiftId, 1000.0, 1000.011)
        val missingReason = JSONObject().put("shift_id", shiftId).put("tank_id", tankId).put("tolerance_threshold", 0.01)
        assertTrue(runCatching { helper.submitShiftReconciliation(missingReason, 1, actorId) }.isFailure)
        val acceptedWithEvidence = JSONObject(missingReason.toString())
            .put("cause", "FUEL_SALE").put("notes", "فرق يتجاوز حد 0.01 لتر")
        val over = helper.submitShiftReconciliation(acceptedWithEvidence, 1, actorId)
        assertEquals(0.011, over.getDouble("variance_liters"), 0.000001)
    }


}
