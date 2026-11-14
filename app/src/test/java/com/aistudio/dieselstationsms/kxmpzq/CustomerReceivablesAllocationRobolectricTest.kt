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
class CustomerReceivablesAllocationRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private lateinit var db: android.database.sqlite.SQLiteDatabase
    private var customerId = 0L
    private var invoiceId = 0L
    private var paymentId = 0L
    private var allocationId = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        db = helper.writableDatabase
        CustomerReceivablesRepository.ensureSchema(db)
        val customerTypeId = db.rawQuery(
            "SELECT id FROM party_types ORDER BY id LIMIT 1",
            null
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        customerId = helper.insertParty(
            JSONObject()
                .put("party_type_id", customerTypeId)
                .put("party_type", "customer")
                .put("commercial_name", "Customer allocation test")
                .put("commercial_name_ar", "عميل اختبار تخصيص")
                .put("is_active", true),
            1
        )
        db.setForeignKeyConstraintsEnabled(false)
        db.update("parties", ContentValues().apply {
            put("current_balance", 80.0)
            put("total_due", 80.0)
        }, "id=?", arrayOf(customerId.toString()))
        invoiceId = db.insertOrThrow("sales_transactions", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("sale_code", "CUST-ALLOC-${UUID.randomUUID().toString().take(8)}")
            put("invoice_number", "CUST-INV-${UUID.randomUUID().toString().take(8)}")
            put("station_id", 1)
            put("shift_id", 1)
            put("customer_party_id", customerId)
            put("subtotal", 100.0)
            put("gross_amount", 100.0)
            put("net_amount", 100.0)
            put("payment_method", "credit")
            put("payment_status", "partial")
            put("paid_amount", 20.0)
            put("remaining_amount", 80.0)
            put("status", "completed")
            put("cashier_id", 1)
            put("is_deleted", 0)
        })
        paymentId = db.insertOrThrow("payments", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("payment_code", "CUST-PAY-${UUID.randomUUID().toString().take(8)}")
            put("station_id", 1)
            put("customer_party_id", customerId)
            put("payment_type", "cash")
            put("payment_method", "cash")
            put("amount", 20.0)
            put("status", "completed")
            put("is_deleted", 0)
        })
        db.beginTransaction()
        try {
            CustomerReceivablesRepository.recordPaymentAllocations(
                db, 1, customerId.toInt(), paymentId, 1L,
                listOf(CustomerReceivablesRepository.PaymentAllocation(invoiceId, 20.0))
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        allocationId = db.rawQuery(
            "SELECT id FROM customer_payment_allocations WHERE payment_id=? AND sales_transaction_id=?",
            arrayOf(paymentId.toString(), invoiceId.toString())
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun partialAllocationReversalReopensOnlyTheInvoiceAndIsIdempotent() {
        val key = "customer-reverse-${UUID.randomUUID()}"
        val first = CustomerReceivablesRepository.reversePaymentAllocation(
            db, 1, customerId.toInt(), allocationId, 5.0, 1L, "تصحيح تخصيص جزئي", key
        )
        assertTrue(first.getBoolean("success"))
        assertEquals(5.0, first.getDouble("reversed_amount"), 0.000001)
        assertEquals(15.0, first.getDouble("invoice_paid_amount"), 0.000001)
        assertEquals(85.0, first.getDouble("invoice_remaining_amount"), 0.000001)

        db.rawQuery(
            "SELECT paid_amount,remaining_amount,payment_status FROM sales_transactions WHERE id=?",
            arrayOf(invoiceId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(15.0, cursor.getDouble(0), 0.000001)
            assertEquals(85.0, cursor.getDouble(1), 0.000001)
            assertEquals("partial", cursor.getString(2))
        }
        db.rawQuery(
            "SELECT current_balance,total_due FROM parties WHERE id=?",
            arrayOf(customerId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(85.0, cursor.getDouble(0), 0.000001)
            assertEquals(85.0, cursor.getDouble(1), 0.000001)
        }
        db.rawQuery(
            "SELECT status,is_deleted FROM payments WHERE id=?",
            arrayOf(paymentId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("completed", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
        val retry = CustomerReceivablesRepository.reversePaymentAllocation(
            db, 1, customerId.toInt(), allocationId, 5.0, 1L, "تصحيح تخصيص جزئي", key
        )
        assertTrue(retry.getBoolean("idempotent"))
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM customer_ledger WHERE transaction_type='payment_allocation_reversal'",
            null
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) })
    }

    @Test
    fun partialReversalCannotExceedRemainingAllocation() {
        assertTrue(runCatching {
            CustomerReceivablesRepository.reversePaymentAllocation(
                db, 1, customerId.toInt(), allocationId, 20.01, 1L,
                "تجاوز الحد", "customer-reversal-over-${UUID.randomUUID()}"
            )
        }.isFailure)
        assertEquals(80.0, db.rawQuery(
            "SELECT total_due FROM parties WHERE id=?", arrayOf(customerId.toString())
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getDouble(0) }, 0.000001)
        assertEquals(0, db.rawQuery(
            "SELECT COUNT(*) FROM customer_payment_allocation_reversals",
            null
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) })
    }

    @Test
    fun failedLedgerWriteRollsBackReversalAndBalanceChanges() {
        db.execSQL("""
            CREATE TRIGGER fail_customer_allocation_reversal_ledger
            BEFORE INSERT ON customer_ledger
            WHEN NEW.transaction_type='payment_allocation_reversal'
            BEGIN SELECT RAISE(ABORT,'forced_customer_allocation_ledger_failure'); END
        """.trimIndent())
        assertTrue(runCatching {
            CustomerReceivablesRepository.reversePaymentAllocation(
                db, 1, customerId.toInt(), allocationId, 5.0, 1L,
                "اختبار ذرية العكس", "customer-reversal-rollback-${UUID.randomUUID()}"
            )
        }.isFailure)
        db.execSQL("DROP TRIGGER IF EXISTS fail_customer_allocation_reversal_ledger")
        assertEquals(80.0, db.rawQuery(
            "SELECT total_due FROM parties WHERE id=?", arrayOf(customerId.toString())
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getDouble(0) }, 0.000001)
        assertEquals(0, db.rawQuery(
            "SELECT COUNT(*) FROM customer_payment_allocation_reversals",
            null
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) })
    }

    @Test
    fun allocationGuardsRejectPaymentAndInvoiceOverAllocation() {
        val secondInvoiceId = db.insertOrThrow("sales_transactions", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("sale_code", "CUST-ALLOC-SECOND-${UUID.randomUUID().toString().take(8)}")
            put("invoice_number", "CUST-INV-SECOND-${UUID.randomUUID().toString().take(8)}")
            put("station_id", 1)
            put("shift_id", 1)
            put("customer_party_id", customerId)
            put("subtotal", 100.0)
            put("gross_amount", 100.0)
            put("net_amount", 100.0)
            put("payment_method", "credit")
            put("payment_status", "pending")
            put("paid_amount", 0.0)
            put("remaining_amount", 100.0)
            put("status", "completed")
            put("cashier_id", 1)
            put("is_deleted", 0)
        })
        assertTrue(runCatching {
            db.beginTransaction()
            try {
                CustomerReceivablesRepository.recordPaymentAllocations(
                    db, 1, customerId.toInt(), paymentId, 1L,
                    listOf(CustomerReceivablesRepository.PaymentAllocation(secondInvoiceId, 1.0))
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }.isFailure)
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM customer_payment_allocations WHERE payment_id=?",
            arrayOf(paymentId.toString())
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) })

        val secondPaymentId = db.insertOrThrow("payments", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("payment_code", "CUST-PAY-OVER-${UUID.randomUUID().toString().take(8)}")
            put("station_id", 1)
            put("customer_party_id", customerId)
            put("payment_type", "cash")
            put("payment_method", "cash")
            put("amount", 90.0)
            put("status", "completed")
            put("is_deleted", 0)
        })
        assertTrue(runCatching {
            db.beginTransaction()
            try {
                CustomerReceivablesRepository.recordPaymentAllocations(
                    db, 1, customerId.toInt(), secondPaymentId, 1L,
                    listOf(CustomerReceivablesRepository.PaymentAllocation(invoiceId, 90.0))
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }.isFailure)
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM customer_payment_allocations WHERE sales_transaction_id=?",
            arrayOf(invoiceId.toString())
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) })
    }


}
