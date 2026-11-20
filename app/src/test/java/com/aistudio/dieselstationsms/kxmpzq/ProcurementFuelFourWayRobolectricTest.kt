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
class ProcurementFuelFourWayRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private lateinit var db: android.database.sqlite.SQLiteDatabase
    private var actorId = 0L
    private var supplierId = 0L
    private var fuelTypeId = 0L
    private var tankId = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        db = helper.writableDatabase
        val roleId = scalar("SELECT id FROM roles ORDER BY id LIMIT 1")
        actorId = db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("username", "proc-${UUID.randomUUID()}")
            put("password_hash", "test-only"); put("full_name", "Procurement test"); put("role_id", roleId); put("station_id", 1)
        })
        val partyTypeId = db.rawQuery("SELECT id FROM party_types WHERE type_code='SUPPLIER' LIMIT 1", null).use {
            if (it.moveToFirst()) it.getLong(0) else scalar("SELECT id FROM party_types ORDER BY id LIMIT 1")
        }
        supplierId = db.insertOrThrow("parties", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("party_code", "SUP-${UUID.randomUUID().toString().take(8)}")
            put("party_type_id", partyTypeId); put("station_id", 1); put("commercial_name", "Supplier test")
            put("commercial_name_ar", "مورد اختبار"); put("is_active", 1); put("is_deleted", 0)
        })
        fuelTypeId = scalar("SELECT id FROM fuel_types WHERE is_deleted=0 ORDER BY id LIMIT 1")
        tankId = db.insertOrThrow("tanks", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("tank_code", "PROC-${UUID.randomUUID()}")
            put("tank_name", "Procurement tank"); put("station_id", 1); put("fuel_type_id", fuelTypeId)
            put("capacity_liters", 1000.0); put("current_quantity", 100.0); put("status", "active"); put("is_deleted", 0)
        })
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun fuelReceiptIsAtomicIdempotentAndStationScoped() {
        val po = helper.createPurchaseOrder(JSONObject().put("item_type", "FUEL").put("item_id", fuelTypeId)
            .put("quantity", 200.0).put("unit_price", 2.0).put("supplier_id", supplierId), 1, actorId)
        val poId = po.getLong("po_id")
        val lineId = scalar("SELECT line_id FROM purchase_order_lines WHERE po_id=?", arrayOf(poId.toString()))
        val payload = JSONObject().put("po_id", poId).put("receipt_key", "fuel-receipt-${UUID.randomUUID()}")
            .put("received_lines", JSONArray().put(JSONObject().put("line_id", lineId).put("received_quantity", 100.0).put("tank_id", tankId)))
        val first = helper.receivePurchaseOrder(payload, 1, actorId)
        val replay = helper.receivePurchaseOrder(JSONObject(payload.toString()), 1, actorId)
        assertTrue(first.getBoolean("success")); assertTrue(replay.getBoolean("replayed"))
        assertEquals(200.0, scalarDouble("SELECT current_quantity FROM tanks WHERE id=?", arrayOf(tankId.toString())), 0.0001)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM tank_refills WHERE po_id=?", arrayOf(poId.toString())))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM fuel_stock_movements WHERE po_id=?", arrayOf(poId.toString())))
        assertTrue(runCatching { helper.receivePurchaseOrder(JSONObject(payload.toString()), 2, actorId) }.isFailure)
    }

    @Test
    fun persistedInvoiceMatchesTaxAndPriceAndVarianceNeedsAccountantApproval() {
        val po = helper.createPurchaseOrder(JSONObject().put("item_type", "FUEL").put("item_id", fuelTypeId)
            .put("quantity", 100.0).put("unit_price", 2.0).put("supplier_id", supplierId), 1, actorId)
        val poId = po.getLong("po_id")
        val lineId = scalar("SELECT line_id FROM purchase_order_lines WHERE po_id=?", arrayOf(poId.toString()))
        helper.receivePurchaseOrder(JSONObject().put("po_id", poId).put("receipt_key", "invoice-receipt-${UUID.randomUUID()}")
            .put("received_lines", JSONArray().put(JSONObject().put("line_id", lineId).put("received_quantity", 100.0).put("tank_id", tankId))), 1, actorId)
        val invoice = JSONObject().put("invoice_number", "INV-${UUID.randomUUID()}").put("subtotal", 250.0)
            .put("tax_amount", 37.5).put("total_amount", 287.5)
            .put("lines", JSONArray().put(JSONObject().put("po_line_id", lineId).put("quantity", 100.0)
                .put("unit_price", 2.5).put("tax_rate", 15.0).put("tax_amount", 37.5).put("line_total", 287.5)))
        val match = helper.matchInvoiceFourWay(JSONObject().put("po_id", poId).put("invoice", invoice), 1)
        assertTrue(!match.getBoolean("matched")); assertTrue(match.getBoolean("requires_accountant_approval"))
        val invoiceId = match.getJSONObject("invoice").getLong("invoice_id")
        assertEquals("VARIANCE", scalarText("SELECT status FROM supplier_invoices WHERE invoice_id=?", arrayOf(invoiceId.toString())))
        val approved = helper.approveSupplierInvoiceMatch(JSONObject().put("invoice_id", invoiceId), 1, actorId)
        assertEquals("APPROVED", approved.getString("status"))
        assertEquals("APPROVED", scalarText("SELECT status FROM supplier_invoices WHERE invoice_id=?", arrayOf(invoiceId.toString())))
    }

    private fun scalar(sql: String, args: Array<String> = emptyArray()): Long = db.rawQuery(sql, args).use { check(it.moveToFirst()); it.getLong(0) }
    private fun scalarDouble(sql: String, args: Array<String>): Double = db.rawQuery(sql, args).use { check(it.moveToFirst()); it.getDouble(0) }
    private fun scalarText(sql: String, args: Array<String>): String = db.rawQuery(sql, args).use { check(it.moveToFirst()); it.getString(0) }
}
