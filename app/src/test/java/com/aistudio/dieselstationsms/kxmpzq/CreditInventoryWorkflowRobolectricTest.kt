package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
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
class CreditInventoryWorkflowRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        helper.writableDatabase
    }

    @After fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test fun `credit sale posts customer debt once, lowers stock, and creates live alert`() {
        val db = helper.writableDatabase
        val roleId = db.rawQuery("SELECT id FROM roles ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val actorId = db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("username", "credit-workflow-${UUID.randomUUID().toString().take(8)}")
            put("password_hash", "test-only"); put("full_name", "مستخدم اختبار البيع الآجل"); put("role_id", roleId); put("station_id", 1)
        })
        val categoryId = db.rawQuery("SELECT id FROM product_categories WHERE is_deleted = 0 ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val unitId = db.rawQuery("SELECT id FROM units ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val productId = helper.insertProduct(JSONObject()
            .put("product_name", "منتج البيع الآجل").put("product_name_ar", "منتج البيع الآجل")
            .put("category_id", categoryId).put("unit_id", unitId).put("purchase_price", 8.0).put("sale_price", 12.0)
            .put("quantity", 5.0).put("minimum_stock", 2.0), 1, actorId)
        helper.adjustProductStock(productId, 5.0, 1, actorId)
        val customerTypeId = db.rawQuery("SELECT id FROM party_types WHERE type_code = 'INDIVIDUAL' AND is_deleted = 0 LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val customerId = db.insertOrThrow("parties", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("party_code", "CUS-${UUID.randomUUID().toString().take(8)}")
            put("party_type_id", customerTypeId); put("station_id", 1); put("commercial_name", "عميل آجل اختبار")
            put("credit_limit", 100.0); put("current_balance", 0.0); put("is_active", 1); put("created_by", actorId)
        })
        db.insertOrThrow("shifts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("shift_code", "SH-${UUID.randomUUID().toString().take(8)}")
            put("station_id", 1); put("shift_date", "2026-08-26"); put("shift_type", "morning"); put("start_time", "2026-08-26 08:00:00")
            put("cashier_id", actorId); put("created_by", actorId); put("status", "open")
        })

        val key = "credit-${UUID.randomUUID()}"
        val payload = JSONObject().put("payment_type", "credit").put("entity_id", customerId).put("amount_paid", 0.0).put("idempotency_key", key)
            .put("products", JSONArray().put(JSONObject().put("product_id", productId).put("quantity", 4.0)))
        val first = helper.completeSale(payload, 1, actorId)
        assertTrue(first.optBoolean("success"))
        val saleId = first.getLong("sale_id")
        val repeat = helper.completeSale(payload, 1, actorId)
        assertTrue(repeat.optBoolean("success")); assertEquals(saleId, repeat.getLong("sale_id"))
        db.rawQuery("SELECT current_balance, total_due FROM parties WHERE id = ?", arrayOf(customerId.toString())).use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(48.0, cursor.getDouble(0), 0.0001); assertEquals(48.0, cursor.getDouble(1), 0.0001)
        }
        assertEquals(1, db.rawQuery("SELECT COUNT(*) FROM customer_ledger WHERE party_id = ? AND transaction_id = ?", arrayOf(customerId.toString(), saleId.toString())).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1.0, db.rawQuery("SELECT quantity FROM products WHERE id = ?", arrayOf(productId.toString())).use { it.moveToFirst(); it.getDouble(0) }, 0.0001)
        val alerts = helper.getStockAlertRecordsContract(JSONObject(), 1).getJSONArray("rows")
        assertTrue((0 until alerts.length()).any { alerts.getJSONObject(it).getLong("product_id") == productId && alerts.getJSONObject(it).getInt("is_resolved") == 0 })
        assertTrue(db.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() })
    }

    @Test fun `approved stocktake creates an auditable adjustment and mirrors final product balance`() {
        val db = helper.writableDatabase
        val roleId = db.rawQuery("SELECT id FROM roles ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val actorId = db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("username", "stocktake-workflow-${UUID.randomUUID().toString().take(8)}")
            put("password_hash", "test-only"); put("full_name", "مستخدم اختبار الجرد"); put("role_id", roleId); put("station_id", 1)
        })
        val categoryId = db.rawQuery("SELECT id FROM product_categories WHERE is_deleted = 0 ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val unitId = db.rawQuery("SELECT id FROM units ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val productId = helper.insertProduct(JSONObject()
            .put("product_name", "منتج اختبار الجرد").put("product_name_ar", "منتج اختبار الجرد")
            .put("category_id", categoryId).put("unit_id", unitId).put("purchase_price", 5.0).put("sale_price", 8.0)
            .put("quantity", 3.0).put("minimum_stock", 1.0), 1, actorId)
        helper.adjustProductStock(productId, 3.0, 1, actorId)
        val warehouseId = helper.ensureOperationalWarehouse(1)
        val stocktakeId = db.insertOrThrow("stocktakes", null, ContentValues().apply {
            put("warehouse_id", warehouseId); put("status", "draft"); put("created_by", actorId); put("notes", "اختبار اعتماد الجرد")
        })
        assertTrue(helper.saveStocktakeDetail(JSONObject().put("stocktake_id", stocktakeId).put("product_id", productId).put("counted_quantity", 1.0), 1) > 0L)
        val details = helper.getStocktakeDetails(stocktakeId, 1)
        assertEquals(1, details.length())
        assertEquals(3.0, details.getJSONObject(0).getDouble("system_quantity"), 0.0001)
        assertEquals(-2.0, details.getJSONObject(0).getDouble("quantity_variance"), 0.0001)
        assertEquals(1, helper.approveStocktake(stocktakeId, 1, actorId))
        assertEquals(1.0, db.rawQuery("SELECT quantity FROM products WHERE id = ?", arrayOf(productId.toString())).use { it.moveToFirst(); it.getDouble(0) }, 0.0001)
        assertEquals(1, db.rawQuery("SELECT COUNT(*) FROM inventory_movements WHERE reference_type = 'stocktake' AND reference_id = ? AND product_id = ?", arrayOf(stocktakeId.toString(), productId.toString())).use { it.moveToFirst(); it.getInt(0) })
        assertTrue(db.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() })
    }

    @Test fun `customer records and optional contact address fields remain editable within the authorized station`() {
        val db = helper.writableDatabase
        val customerTypeId = db.rawQuery("SELECT id FROM party_types WHERE type_code = 'INDIVIDUAL' AND is_deleted = 0 LIMIT 1", null).use { check(it.moveToFirst()); it.getInt(0) }
        val customerId = helper.insertParty(JSONObject()
            .put("party_type", "customer").put("party_type_id", customerTypeId)
            .put("commercial_name", "عميل قابل للتعديل").put("commercial_name_ar", "عميل قابل للتعديل")
            .put("credit_limit", 250.0).put("is_active", 1), 1)
        assertEquals(1, helper.updateParty(customerId, JSONObject()
            .put("party_type", "customer").put("party_type_id", customerTypeId)
            .put("commercial_name", "العميل بعد التعديل").put("commercial_name_ar", "العميل بعد التعديل")
            .put("credit_limit", 300.0).put("is_active", 1), 1))
        val contactId = helper.addPartyContact(JSONObject()
            .put("party_id", customerId).put("contact_name", "مسؤول المشتريات")
            .put("job_title", "مدير").put("phone", "777000000").put("email", "purchasing@example.test"), 1)
        assertEquals(1, helper.updatePartyContact(contactId, JSONObject()
            .put("contact_name", "مسؤول الحسابات").put("job_title", "")
            .put("phone", "").put("email", ""), 1))
        val addressId = helper.addPartyAddress(JSONObject()
            .put("party_id", customerId).put("address_type", "main").put("address_line1", "شارع المحطة")
            .put("city", "صنعاء").put("state", "الأمانة").put("country", "اليمن"), 1)
        assertEquals(1, helper.updatePartyAddress(addressId, JSONObject()
            .put("address_type", "main").put("address_line1", "موقع العميل الجديد")
            .put("city", "").put("state", "").put("country", ""), 1))
        db.rawQuery("SELECT commercial_name, credit_limit FROM parties WHERE id = ? AND station_id = 1", arrayOf(customerId.toString())).use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals("العميل بعد التعديل", cursor.getString(0)); assertEquals(300.0, cursor.getDouble(1), 0.0001)
        }
        db.rawQuery("SELECT contact_name, job_title, phone, email FROM party_contacts WHERE id = ?", arrayOf(contactId.toString())).use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals("مسؤول الحسابات", cursor.getString(0)); assertEquals("", cursor.getString(1)); assertEquals("", cursor.getString(2)); assertEquals("", cursor.getString(3))
        }
        db.rawQuery("SELECT address_line1, city, state, country FROM party_addresses WHERE id = ?", arrayOf(addressId.toString())).use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals("موقع العميل الجديد", cursor.getString(0)); assertEquals("", cursor.getString(1)); assertEquals("", cursor.getString(2)); assertEquals("", cursor.getString(3))
        }
        assertTrue(db.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() })
    }

    @Test fun `pos inventory lookups use the composite indexes rather than full table scans`() {
        val db = helper.writableDatabase
        val levelPlan = db.rawQuery(
            "EXPLAIN QUERY PLAN SELECT quantity_on_hand FROM inventory_levels WHERE product_id = ? AND warehouse_id = ?",
            arrayOf("101", "202")
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(3) }
        val movementPlan = db.rawQuery(
            "EXPLAIN QUERY PLAN SELECT id, quantity_after FROM inventory_movements WHERE station_id = ? AND product_id = ? AND warehouse_id = ? AND is_deleted = 0",
            arrayOf("1", "101", "202")
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(3) }
        assertTrue("خطة مستوى المخزون لا تستخدم فهرساً: $levelPlan", levelPlan.contains("USING INDEX") || levelPlan.contains("USING COVERING INDEX"))
        assertTrue("خطة حركات المخزون لا تستخدم الفهرس المركب الموجود: $movementPlan", movementPlan.contains("idx_inventory_movements_station_product"))
    }

    @Test fun `vehicle trip workspace joins actual fleet records and excludes another station`() {
        val db = helper.writableDatabase
        val partyTypeId = db.rawQuery("SELECT id FROM party_types WHERE is_deleted = 0 ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val stationTwo = db.insertOrThrow("stations", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("station_code", "TRP-${UUID.randomUUID().toString().take(8)}")
            put("station_name", "محطة اختبار العزل الثانية"); put("station_name_ar", "محطة اختبار العزل الثانية")
        }).toInt()

        fun createTrip(stationId: Int, suffix: String): Long {
            val partyId = db.insertOrThrow("parties", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString()); put("party_code", "PTY-${suffix}-${UUID.randomUUID().toString().take(6)}")
                put("party_type_id", partyTypeId); put("station_id", stationId); put("commercial_name", "مالك المركبة $suffix")
                put("is_active", 1); put("is_deleted", 0)
            })
            val vehicleId = db.insertOrThrow("vehicles", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString()); put("vehicle_code", "VEH-$suffix-${UUID.randomUUID().toString().take(6)}")
                put("party_id", partyId); put("plate_number", "لوحة-$suffix"); put("current_odometer", 1200.0)
                put("status", "active"); put("is_deleted", 0)
            })
            val driverId = db.insertOrThrow("drivers", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString()); put("driver_code", "DRV-$suffix-${UUID.randomUUID().toString().take(6)}")
                put("station_id", stationId); put("vehicle_id", vehicleId); put("full_name", "السائق $suffix")
                put("full_name_ar", "السائق $suffix"); put("status", "active"); put("is_deleted", 0)
            })
            db.insertOrThrow("vehicle_locations", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString()); put("vehicle_id", vehicleId); put("latitude", 15.3694)
                put("longitude", 44.1910); put("speed", 30.0); put("odometer", 1242.5); put("location_time", "2026-08-26 10:30:00")
            })
            return helper.saveOperationalRecord("vehicle_trips", JSONObject()
                .put("station_id", stationId).put("vehicle_id", vehicleId).put("driver_id", driverId).put("trip_date", "2026-08-26")
                .put("start_location", "المحطة").put("end_location", "موقع العميل")
                .put("distance_km", 42.5).put("fuel_consumed", 8.5).put("fuel_cost", 7600.0)
                .put("start_odometer", 1200.0).put("end_odometer", 1242.5).put("trip_purpose", "توصيل"), 0)
        }

        val firstTripId = createTrip(1, "ONE")
        createTrip(stationTwo, "TWO")
        val firstVehicleId = db.rawQuery("SELECT vehicle_id FROM vehicle_trips WHERE id = ?", arrayOf(firstTripId.toString())).use { check(it.moveToFirst()); it.getLong(0) }
        val workspace = helper.getVehicleTripWorkspace(JSONObject().put("limit", 20).put("vehicle_id", firstVehicleId).put("sort_by", "trip_date").put("sort_dir", "desc"), 1)
        assertEquals(1, workspace.getInt("total_count"))
        assertEquals(1, workspace.getJSONArray("rows").length())
        val row = workspace.getJSONArray("rows").getJSONObject(0)
        assertEquals(firstTripId, row.getLong("id"))
        assertEquals("scheduled", row.getString("trip_status"))
        assertEquals("السائق ONE", row.getString("driver_name"))
        assertEquals("المحطة", row.getString("start_location"))
        assertEquals(15.3694, row.getDouble("last_latitude"), 0.0001)
        assertEquals(5.0, row.getDouble("efficiency_km_per_liter"), 0.0001)
        assertEquals(1, workspace.getJSONArray("vehicles").length())
        assertEquals(1, workspace.getJSONArray("drivers").length())
        assertEquals(42.5, workspace.getJSONObject("statistics").getDouble("total_distance_km"), 0.0001)
        assertEquals(1, helper.getVehicleTripWorkspace(JSONObject().put("vehicle_id", row.getLong("vehicle_id")), 1).getInt("total_count"))
        assertEquals(0, helper.getVehicleTripWorkspace(JSONObject().put("vehicle_id", firstVehicleId), stationTwo).getInt("total_count"))
        assertEquals("scheduled", helper.getVehicleTripDetails(firstTripId, 1)?.getString("trip_status"))
        assertTrue(helper.getVehicleTripTimeline(firstTripId, 1).length() >= 1)
        val started = helper.updateVehicleTripStatus(firstTripId, "active", "غادرت المركبة المحطة", 1, 0)
        assertEquals("active", started.getString("trip_status"))
        assertEquals("active", helper.getVehicleTripDetails(firstTripId, 1)?.getString("trip_status"))
        val timeline = helper.getVehicleTripTimeline(firstTripId, 1)
        assertTrue((0 until timeline.length()).any { timeline.getJSONObject(it).getString("event_type") == "created" })
        assertTrue((0 until timeline.length()).any { timeline.getJSONObject(it).getString("event_type") == "started" })
        assertEquals(1, helper.getVehicleTripStatistics(JSONObject().put("trip_status", "active"), 1).getInt("active_count"))
        val trackingForStationOne = helper.getVehicleTrackingStatus(JSONObject().put("station_id", 1).put("vehicle_id", firstVehicleId))
        assertEquals(1, trackingForStationOne.length())
        assertEquals(firstVehicleId, trackingForStationOne.getJSONObject(0).getLong("vehicle_id"))
        assertEquals(15.3694, trackingForStationOne.getJSONObject(0).getDouble("last_latitude"), 0.0001)
        val routeForStationOne = helper.getVehicleRouteRecords(JSONObject().put("station_id", 1).put("vehicle_id", firstVehicleId))
        assertEquals(1, routeForStationOne.length())
        assertEquals(firstVehicleId, routeForStationOne.getJSONObject(0).getLong("vehicle_id"))
        val normalizedLocationId = helper.saveOperationalRecord("vehicle_locations", JSONObject()
            .put("station_id", 1).put("vehicle_id", firstVehicleId).put("latitude", 15.3699).put("longitude", 44.1919)
            .put("location_time", "2026-08-27T10:59"), 0)
        assertEquals("2026-08-27 10:59:00", db.rawQuery("SELECT location_time FROM vehicle_locations WHERE id = ?", arrayOf(normalizedLocationId.toString())).use { check(it.moveToFirst()); it.getString(0) })
        db.beginTransaction()
        try {
            repeat(480) { index ->
                db.insertOrThrow("vehicle_locations", null, ContentValues().apply {
                    put("uuid", UUID.randomUUID().toString()); put("vehicle_id", firstVehicleId)
                    put("latitude", 15.3700 + (index / 100000.0)); put("longitude", 44.1920 + (index / 100000.0))
                    put("speed", 35.0); put("location_time", "2026-08-27 %02d:%02d:00".format(11 + (index / 60), index % 60))
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val routePlan = db.rawQuery(
            "EXPLAIN QUERY PLAN SELECT id, vehicle_id, latitude, longitude, speed, heading, fuel_level, odometer, altitude, accuracy, location_time FROM vehicle_locations WHERE vehicle_id = ? AND location_time >= ? AND location_time < ? ORDER BY location_time ASC, id ASC LIMIT ?",
            arrayOf(firstVehicleId.toString(), "2026-08-27", "2026-08-28", "1000")
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(3) }
        assertTrue("خطة مسار GPS لا تستخدم الفهرس المركب: $routePlan", routePlan.contains("idx_vehicle_locations_vehicle_time"))
        assertTrue("خطة مسار GPS تستخدم فرزاً مؤقتاً: $routePlan", !routePlan.contains("TEMP B-TREE", ignoreCase = true))
        val longRoute = helper.getVehicleRouteRecords(JSONObject().put("station_id", 1).put("vehicle_id", firstVehicleId).put("from_date", "2026-08-27").put("to_date", "2026-08-27").put("limit", 1000))
        assertEquals(481, longRoute.length())
        assertEquals("2026-08-27 10:59:00", longRoute.getJSONObject(0).getString("location_time"))
        assertEquals(1, helper.getVehicleRouteRecords(JSONObject().put("station_id", 1).put("vehicle_id", firstVehicleId).put("from_date", "2026-08-26").put("to_date", "2026-08-26")).length())
        assertEquals(0, helper.getVehicleTrackingStatus(JSONObject().put("station_id", stationTwo).put("vehicle_id", firstVehicleId)).length())
        try {
            helper.getVehicleRouteRecords(JSONObject().put("station_id", stationTwo).put("vehicle_id", firstVehicleId))
            throw AssertionError("يجب رفض قراءة مسار مركبة خارج نطاق المحطة")
        } catch (_: IllegalArgumentException) { }
        try {
            helper.updateVehicleTripStatus(firstTripId, "completed", "محاولة من محطة أخرى", stationTwo, 0)
            throw AssertionError("يجب رفض تغيير حالة رحلة خارج نطاق المحطة")
        } catch (_: IllegalArgumentException) { }
        assertTrue(db.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() })
    }

    @Test fun `vehicle expense workspace computes SQLite financial data and excludes another station`() {
        val db = helper.writableDatabase
        val partyTypeId = db.rawQuery("SELECT id FROM party_types WHERE is_deleted = 0 ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val stationTwo = db.insertOrThrow("stations", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("station_code", "EXP-${UUID.randomUUID().toString().take(8)}")
            put("station_name", "محطة عزل المصروفات الثانية"); put("station_name_ar", "محطة عزل المصروفات الثانية")
        }).toInt()
        fun vehicleFor(stationId: Int, suffix: String): Long {
            val partyId = db.insertOrThrow("parties", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString()); put("party_code", "EP-$suffix-${UUID.randomUUID().toString().take(6)}")
                put("party_type_id", partyTypeId); put("station_id", stationId); put("commercial_name", "مالك مصروفات $suffix")
                put("is_active", 1); put("is_deleted", 0)
            })
            return db.insertOrThrow("vehicles", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString()); put("vehicle_code", "EXP-$suffix-${UUID.randomUUID().toString().take(6)}")
                put("party_id", partyId); put("plate_number", "لوحة مصروف-$suffix"); put("brand", "Toyota"); put("model", "Hilux")
                put("status", "active"); put("is_deleted", 0)
            })
        }
        val stationOneVehicle = vehicleFor(1, "ONE")
        val stationTwoVehicle = vehicleFor(stationTwo, "TWO")
        fun expense(stationId: Int, vehicleId: Long, type: String, date: String, amount: Double, invoice: String = ""): Long =
            helper.saveOperationalRecord("vehicle_expenses", JSONObject().put("station_id", stationId).put("vehicle_id", vehicleId)
                .put("expense_type", type).put("expense_date", date).put("amount", amount).put("description", "وصف $type").put("invoice_path", invoice), 0)
        db.insertOrThrow("vehicle_trips", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("vehicle_id", stationOneVehicle); put("trip_date", "2026-08-26")
            put("distance_km", 100.0); put("trip_status", "completed")
        })
        val attachedId = expense(1, stationOneVehicle, "صيانة", "2026-08-26", 2500.0, "/data/user/0/test/files/invoices/expense.pdf")
        expense(1, stationOneVehicle, "وقود", "2026-08-27", 500.0)
        expense(stationTwo, stationTwoVehicle, "صيانة", "2026-08-26", 9000.0)
        val workspace = helper.getVehicleExpenseWorkspace(JSONObject().put("from_date", "2026-08-26").put("to_date", "2026-08-27").put("limit", 20), 1)
        assertEquals(2, workspace.getInt("total_count"))
        assertEquals(3000.0, workspace.getJSONObject("summary").getDouble("total_expenses"), 0.0001)
        assertEquals(100.0, workspace.getJSONObject("summary").getDouble("total_distance_km"), 0.0001)
        assertEquals(30.0, workspace.getJSONObject("summary").getDouble("cost_per_km"), 0.0001)
        assertEquals("Toyota Hilux", workspace.getJSONObject("summary").getString("top_vehicle_name"))
        assertEquals("صيانة", workspace.getJSONObject("summary").getString("top_expense_type"))
        assertEquals(2, workspace.getJSONArray("rows").length())
        assertEquals("Toyota Hilux", workspace.getJSONArray("rows").getJSONObject(0).getString("vehicle_name"))
        assertEquals("لوحة مصروف-ONE", workspace.getJSONArray("rows").getJSONObject(0).getString("plate_number"))
        assertEquals(2, workspace.getJSONArray("expense_types").length())
        assertEquals(1, workspace.getJSONArray("vehicles").length())
        val attachedOnly = helper.getVehicleExpenseWorkspace(JSONObject().put("invoice_state", "attached"), 1)
        assertEquals(1, attachedOnly.getInt("total_count"))
        assertEquals(attachedId, attachedOnly.getJSONArray("rows").getJSONObject(0).getLong("id"))
        assertEquals(1, helper.getVehicleExpenseWorkspace(JSONObject().put("invoice_state", "missing"), 1).getInt("total_count"))
        assertEquals(0, helper.getVehicleExpenseWorkspace(JSONObject().put("vehicle_id", stationOneVehicle), stationTwo).getInt("total_count"))
        assertEquals(attachedId, helper.getVehicleExpenseDetails(attachedId, 1)?.getLong("id"))
        assertEquals("/data/user/0/test/files/invoices/expense.pdf", helper.getVehicleExpenseInvoicePath(attachedId, 1))
        assertTrue(helper.getVehicleExpenseDetails(attachedId, stationTwo) == null)
        assertTrue(helper.getVehicleExpenseInvoicePath(attachedId, stationTwo) == null)
        val plan = db.rawQuery(
            "EXPLAIN QUERY PLAN SELECT id, amount FROM vehicle_expenses WHERE vehicle_id = ? AND expense_date >= ? AND expense_date <= ? ORDER BY expense_date DESC, id DESC",
            arrayOf(stationOneVehicle.toString(), "2026-08-26", "2026-08-27")
        ).use { cursor -> buildString { while (cursor.moveToNext()) append(cursor.getString(3)).append('\n') } }
        assertTrue("خطة مصروفات المركبات لا تستخدم فهرس التاريخ المركب: $plan", plan.contains("idx_vehicle_expenses_vehicle_date"))
        assertTrue(db.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() })
    }
}
