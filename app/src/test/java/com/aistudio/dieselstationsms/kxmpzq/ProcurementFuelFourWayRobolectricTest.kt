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
        assertEquals("VARIANCE", scalarText("SELECT match_status FROM supplier_invoices WHERE id=?", arrayOf(invoiceId.toString())))
        val cashierId = createUserForRole("CASHIER")
        assertTrue(runCatching {
            helper.approveSupplierInvoiceMatch(JSONObject().put("invoice_id", invoiceId), 1, cashierId)
        }.isFailure)
        assertEquals("VARIANCE", scalarText("SELECT match_status FROM supplier_invoices WHERE id=?", arrayOf(invoiceId.toString())))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM supplier_invoices WHERE id=? AND accountant_approved_by IS NOT NULL", arrayOf(invoiceId.toString())))

        val accountantId = createUserForRole("ACCOUNTANT")
        val approved = helper.approveSupplierInvoiceMatch(JSONObject().put("invoice_id", invoiceId), 1, accountantId)
        assertEquals("APPROVED", approved.getString("status"))
        assertEquals("APPROVED", scalarText("SELECT match_status FROM supplier_invoices WHERE id=?", arrayOf(invoiceId.toString())))
        assertEquals(accountantId, scalar("SELECT accountant_approved_by FROM supplier_invoices WHERE id=?", arrayOf(invoiceId.toString())))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM supplier_invoices WHERE id=? AND accountant_approved_at IS NOT NULL", arrayOf(invoiceId.toString())))
    }


    @Test
    fun persistedInvoiceRetryRejectsChangedHeaderOrLineAndDuplicateLines() {
        val po = helper.createPurchaseOrder(JSONObject().put("item_type", "FUEL").put("item_id", fuelTypeId)
            .put("quantity", 100.0).put("unit_price", 2.0).put("supplier_id", supplierId), 1, actorId)
        val poId = po.getLong("po_id")
        val lineId = scalar("SELECT line_id FROM purchase_order_lines WHERE po_id=?", arrayOf(poId.toString()))
        helper.receivePurchaseOrder(JSONObject().put("po_id", poId).put("receipt_key", "immutable-invoice-${UUID.randomUUID()}")
            .put("received_lines", JSONArray().put(JSONObject().put("line_id", lineId).put("received_quantity", 100.0).put("tank_id", tankId))), 1, actorId)

        val invoiceNumber = "IMM-${UUID.randomUUID()}"
        fun invoice(price: Double) = JSONObject().put("invoice_number", invoiceNumber)
            .put("subtotal", 100.0 * price).put("tax_amount", 100.0 * price * 0.15)
            .put("total_amount", 100.0 * price * 1.15)
            .put("lines", JSONArray().put(JSONObject().put("po_line_id", lineId).put("quantity", 100.0)
                .put("unit_price", price).put("tax_rate", 15.0)
                .put("tax_amount", 100.0 * price * 0.15).put("line_total", 100.0 * price * 1.15)))

        val first = helper.matchInvoiceFourWay(JSONObject().put("po_id", poId).put("invoice", invoice(2.5)), 1)
        val invoiceId = first.getJSONObject("invoice").getLong("invoice_id")
        val replay = helper.matchInvoiceFourWay(JSONObject().put("po_id", poId).put("invoice", invoice(2.5)), 1)
        assertEquals(invoiceId, replay.getJSONObject("invoice").getLong("invoice_id"))
        assertTrue(runCatching {
            helper.matchInvoiceFourWay(JSONObject().put("po_id", poId).put("invoice", invoice(2.75)), 1)
        }.isFailure)
        assertEquals(287.5, scalarDouble("SELECT total_amount FROM supplier_invoices WHERE id=?", arrayOf(invoiceId.toString())), 0.0001)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM supplier_invoice_lines WHERE invoice_id=?", arrayOf(invoiceId.toString())))

        val secondPo = helper.createPurchaseOrder(JSONObject().put("item_type", "FUEL").put("item_id", fuelTypeId)
            .put("quantity", 20.0).put("unit_price", 2.0).put("supplier_id", supplierId), 1, actorId)
        val secondPoId = secondPo.getLong("po_id")
        val secondLine = scalar("SELECT line_id FROM purchase_order_lines WHERE po_id=?", arrayOf(secondPoId.toString()))
        helper.receivePurchaseOrder(JSONObject().put("po_id", secondPoId).put("receipt_key", "duplicate-lines-${UUID.randomUUID()}")
            .put("received_lines", JSONArray().put(JSONObject().put("line_id", secondLine).put("received_quantity", 20.0).put("tank_id", tankId))), 1, actorId)
        val duplicateNumber = "DUP-${UUID.randomUUID()}"
        val duplicated = JSONObject().put("invoice_number", duplicateNumber)
            .put("subtotal", 40.0).put("tax_amount", 6.0).put("total_amount", 46.0)
            .put("lines", JSONArray()
                .put(JSONObject().put("po_line_id", secondLine).put("quantity", 20.0).put("unit_price", 2.0).put("tax_rate", 15.0).put("tax_amount", 6.0).put("line_total", 46.0))
                .put(JSONObject().put("po_line_id", secondLine).put("quantity", 20.0).put("unit_price", 2.0).put("tax_rate", 15.0).put("tax_amount", 6.0).put("line_total", 46.0)))
        assertTrue(runCatching {
            helper.matchInvoiceFourWay(JSONObject().put("po_id", secondPoId).put("invoice", duplicated), 1)
        }.isFailure)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM supplier_invoices WHERE station_id=1 AND invoice_number=?",
            arrayOf(duplicateNumber)))
    }

    private fun createUserForRole(roleCode: String): Long {
        val roleId = scalar("SELECT id FROM roles WHERE role_code=? AND is_active=1 AND is_deleted=0 LIMIT 1", arrayOf(roleCode))
        return db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", "proc-${roleCode.lowercase()}-${UUID.randomUUID()}")
            put("password_hash", "test-only"); put("full_name", "Procurement $roleCode")
            put("role_id", roleId); put("station_id", 1); put("status", "active"); put("is_deleted", 0)
        })
    }

    @Test
    fun purchaseOrderCreationRejectsMissingSupplierAndInvalidCommercialInputs() {
        val base = JSONObject().put("item_type", "FUEL").put("item_id", fuelTypeId)
            .put("quantity", 10.0).put("unit_price", 2.0)
        assertTrue(runCatching { helper.createPurchaseOrder(base, 1, actorId) }.isFailure)
        assertTrue(runCatching {
            helper.createPurchaseOrder(JSONObject(base.toString()).put("supplier_id", supplierId).put("unit_price", -1.0), 1, actorId)
        }.isFailure)
        assertTrue(runCatching {
            helper.createPurchaseOrder(JSONObject(base.toString()).put("supplier_id", supplierId).put("item_id", Long.MAX_VALUE), 1, actorId)
        }.isFailure)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM purchase_orders"))
        val valid = helper.createPurchaseOrder(JSONObject(base.toString()).put("supplier_id", supplierId), 1, actorId)
        assertTrue(valid.getLong("po_id") > 0L)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM purchase_orders"))
    }

    @Test
    fun v72UpgradeSeedsVarianceApprovalPermissionWithoutChangingInvoices() {
        val po = helper.createPurchaseOrder(JSONObject().put("item_type", "FUEL").put("item_id", fuelTypeId)
            .put("quantity", 100.0).put("unit_price", 2.0).put("supplier_id", supplierId), 1, actorId)
        val poId = po.getLong("po_id")
        val lineId = scalar("SELECT line_id FROM purchase_order_lines WHERE po_id=?", arrayOf(poId.toString()))
        helper.receivePurchaseOrder(JSONObject().put("po_id", poId).put("receipt_key", "v72-migration-${UUID.randomUUID()}")
            .put("received_lines", JSONArray().put(JSONObject().put("line_id", lineId).put("received_quantity", 100.0).put("tank_id", tankId))), 1, actorId)
        val invoice = JSONObject().put("invoice_number", "MIG-${UUID.randomUUID()}")
            .put("subtotal", 200.0).put("tax_amount", 0.0).put("total_amount", 200.0)
            .put("lines", JSONArray().put(JSONObject().put("po_line_id", lineId).put("quantity", 100.0)
                .put("unit_price", 2.0).put("tax_rate", 0.0).put("tax_amount", 0.0).put("line_total", 200.0)))
        val matched = helper.matchInvoiceFourWay(JSONObject().put("po_id", poId).put("invoice", invoice), 1)
        val invoiceId = matched.getJSONObject("invoice").getLong("invoice_id")
        val savedTotal = scalarDouble("SELECT total_amount FROM supplier_invoices WHERE id=?", arrayOf(invoiceId.toString()))

        val permissionId = scalar("SELECT id FROM permissions WHERE permission_code='supplier_invoice.approve'")
        db.delete("role_permissions", "permission_id=?", arrayOf(permissionId.toString()))
        db.delete("permissions", "id=?", arrayOf(permissionId.toString()))
        DatabaseHelper.closeInstance()
        val versionDb = android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath(DatabaseHelper.DATABASE_NAME).absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
        )
        try { versionDb.execSQL("PRAGMA user_version=72") } finally { versionDb.close() }
        DatabaseHelper.closeInstance()
        helper = DatabaseHelper.getInstance(context)
        db = helper.writableDatabase

        assertEquals(76L, scalar("PRAGMA user_version"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM permissions WHERE permission_code='supplier_invoice.approve' AND is_active=1 AND is_deleted=0"))
        assertEquals(savedTotal, scalarDouble("SELECT total_amount FROM supplier_invoices WHERE id=?", arrayOf(invoiceId.toString())), 0.0001)
        assertEquals(1L, scalar("""SELECT COUNT(*) FROM role_permissions rp
            JOIN roles r ON r.id=rp.role_id JOIN permissions p ON p.id=rp.permission_id
            WHERE p.permission_code='supplier_invoice.approve' AND r.role_code='ACCOUNTANT'
              AND rp.can_approve=1 AND rp.is_deleted=0""".trimIndent()))
    }

    private fun scalar(sql: String, args: Array<String> = emptyArray()): Long = db.rawQuery(sql, args).use { check(it.moveToFirst()); it.getLong(0) }
    private fun scalarDouble(sql: String, args: Array<String>): Double = db.rawQuery(sql, args).use { check(it.moveToFirst()); it.getDouble(0) }
    private fun scalarText(sql: String, args: Array<String>): String = db.rawQuery(sql, args).use { check(it.moveToFirst()); it.getString(0) }
}
