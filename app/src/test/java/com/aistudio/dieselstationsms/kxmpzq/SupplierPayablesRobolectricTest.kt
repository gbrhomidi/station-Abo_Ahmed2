package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aistudio.dieselstationsms.kxmpzq.sms.DebtDueDateRepository
import com.aistudio.dieselstationsms.kxmpzq.suppliers.SupplierPayablesRepository
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
class SupplierPayablesRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private lateinit var db: android.database.sqlite.SQLiteDatabase
    private var supplierId: Long = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        DebtDueDateRepository.ensureSchema(helper)
        db = helper.writableDatabase
        SupplierPayablesRepository.ensureSchema(db)
        val partyTypeId = db.rawQuery(
            "SELECT id FROM party_types WHERE type_code='SUPPLIER' AND is_deleted=0 LIMIT 1",
            null
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        supplierId = helper.insertParty(
            JSONObject()
                .put("party_type_id", partyTypeId)
                .put("party_type", "supplier")
                .put("commercial_name", "Supplier payable test")
                .put("commercial_name_ar", "مورد اختبار الذمم")
                .put("payment_terms", "صافي 15 يوم")
                .put("is_active", true),
            1
        )
        DebtDueDateRepository.persistPartyTerms(helper, supplierId, 1, "صافي 15 يوم")
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    private fun createCompletedSupplierPayment(amount: Double, suffix: String): Long =
        db.insertOrThrow("payments", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("payment_code", "SP-TEST-$suffix")
            put("station_id", 1)
            put("supplier_party_id", supplierId)
            put("payment_type", "cash")
            put("payment_method", "cash")
            put("amount", amount)
            put("status", "completed")
            put("is_refund", 0)
            put("is_deleted", 0)
            put("created_at", "2026-11-05 10:00:00")
        })

    @Test
    fun fifoAllocationsUpdateInvoiceBalancesAndAreIdempotent() {
        val firstInvoice = SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "fuel_refill", 91001, "REF-91001",
            100.0, "2026-10-01", null, 1
        )
        val secondInvoice = SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "product_purchase", 91002, "PROD-91002",
            200.0, "2026-10-15", null, 1
        )
        assertEquals(firstInvoice, SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "fuel_refill", 91001, "REF-91001",
            100.0, "2026-10-01", null, 1
        ))
        db.rawQuery(
            "SELECT due_date,payment_terms_days_at_purchase,due_date_source FROM supplier_invoices WHERE id=?",
            arrayOf(firstInvoice.toString())
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals("2026-10-16", cursor.getString(0))
            assertEquals(15, cursor.getInt(1))
            assertEquals("purchase_creation", cursor.getString(2))
        }

        val paymentId = createCompletedSupplierPayment(125.0, "FIFO")
        val allocation = SupplierPayablesRepository.allocatePayment(
            db, 1, supplierId, paymentId, 125.0, 1
        )
        assertTrue(allocation.getBoolean("success"))
        assertEquals(125.0, allocation.getDouble("allocated_amount"), 0.0001)
        assertEquals(0.0, allocation.getDouble("unapplied_amount"), 0.0001)
        assertEquals(2, allocation.getInt("invoice_allocation_count"))

        db.rawQuery(
            "SELECT paid_amount,remaining_amount,status FROM supplier_invoices WHERE id=?",
            arrayOf(firstInvoice.toString())
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(100.0, cursor.getDouble(0), 0.0001)
            assertEquals(0.0, cursor.getDouble(1), 0.0001)
            assertEquals("paid", cursor.getString(2))
        }
        db.rawQuery(
            "SELECT paid_amount,remaining_amount,status FROM supplier_invoices WHERE id=?",
            arrayOf(secondInvoice.toString())
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(25.0, cursor.getDouble(0), 0.0001)
            assertEquals(175.0, cursor.getDouble(1), 0.0001)
            assertEquals("partial", cursor.getString(2))
        }

        val repeated = SupplierPayablesRepository.allocatePayment(
            db, 1, supplierId, paymentId, 125.0, 1
        )
        assertTrue(repeated.getBoolean("idempotent"))
        assertEquals(125.0, repeated.getDouble("allocated_amount"), 0.0001)
        assertEquals(2, db.rawQuery(
            "SELECT COUNT(*) FROM supplier_invoice_allocations WHERE payment_id=? AND status='posted' AND is_deleted=0",
            arrayOf(paymentId.toString())
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) })

        val aging = SupplierPayablesRepository.getSupplierAging(db, 1, supplierId, "2026-11-05")
        val byCurrency = aging.getJSONArray("currency_totals")
        assertEquals(1, byCurrency.length())
        assertEquals(175.0, byCurrency.getJSONObject(0).getDouble("open_balance"), 0.0001)
        assertEquals(175.0, byCurrency.getJSONObject(0).getDouble("overdue_amount"), 0.0001)
        assertEquals(175.0, byCurrency.getJSONObject(0).getDouble("overdue_1_30"), 0.0001)

        val rows = SupplierPayablesRepository.getSupplierInvoices(db, 1, supplierId)
        assertEquals(2, rows.length())
        assertEquals("overdue", rows.getJSONObject(0).optString("maturity_status"))
        assertEquals("paid", rows.getJSONObject(1).optString("maturity_status"))
    }

    @Test
    fun paymentAllocationIsAtomicWhenCalledOutsideAnExistingTransaction() {
        val invoiceId = SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 92001, "MAN-92001",
            80.0, "2026-10-01", null, 1
        )
        val paymentId = createCompletedSupplierPayment(30.0, "ATOMIC")
        val result = SupplierPayablesRepository.allocatePayment(
            db, 1, supplierId, paymentId, 30.0, 1, invoiceId
        )
        assertFalse(result.getBoolean("idempotent"))
        assertEquals(30.0, result.getDouble("allocated_amount"), 0.0001)
        db.rawQuery(
            "SELECT paid_amount,remaining_amount,status FROM supplier_invoices WHERE id=?",
            arrayOf(invoiceId.toString())
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(30.0, cursor.getDouble(0), 0.0001)
            assertEquals(50.0, cursor.getDouble(1), 0.0001)
            assertEquals("partial", cursor.getString(2))
        }
    }

    @Test
    fun supplierDataCannotBeReadAcrossStationScope() {
        var rejected = false
        try {
            SupplierPayablesRepository.getSupplierInvoices(db, 2, supplierId)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Supplier invoices must remain station scoped", rejected)
    }
}
