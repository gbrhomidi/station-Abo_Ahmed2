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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    private fun createCompletedSupplierPayment(amount: Double, suffix: String, currencyId: Long? = null): Long =
        db.insertOrThrow("payments", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("payment_code", "SP-TEST-$suffix")
            put("station_id", 1)
            put("supplier_party_id", supplierId)
            put("payment_type", "cash")
            put("payment_method", "cash")
            put("amount", amount)
            if (currencyId != null && currencyId > 0L) put("currency_id", currencyId)
            put("status", "completed")
            put("is_refund", 0)
            put("is_deleted", 0)
            put("created_at", "2026-11-05 10:00:00")
        })

    @Test
    fun fifoAllocationsUpdateInvoiceBalancesAndAreIdempotent() {
        val firstInvoice = SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 91001, "MAN-91001",
            100.0, "2026-10-01", null, 1
        )
        val secondInvoice = SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 91002, "MAN-91002",
            200.0, "2026-10-15", null, 1
        )
        assertEquals(firstInvoice, SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 91001, "MAN-91001",
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
    fun concurrentIdenticalInvoiceRequestsReturnOneInvoiceId() {
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val workers = (1..2).map {
            Thread {
                try {
                    start.await(5, TimeUnit.SECONDS)
                    results += SupplierPayablesRepository.createPurchaseInvoice(
                        db, 1, supplierId, "manual", 96001, "MAN-CONCURRENT-96001",
                        250.0, "2026-10-01", null, 1
                    )
                } catch (error: Throwable) {
                    failures += error
                } finally {
                    done.countDown()
                }
            }.apply { name = "supplier-invoice-worker-$it" }
        }
        workers.forEach(Thread::start)
        start.countDown()
        assertTrue("انتهت مهلة اختبار إنشاء الفاتورة المتزامن", done.await(10, TimeUnit.SECONDS))
        workers.forEach(Thread::join)
        assertTrue("فشل طلب متزامن لإنشاء الفاتورة: $failures", failures.isEmpty())
        assertEquals(2, results.size)
        assertEquals(1, results.toSet().size)
        db.rawQuery(
            "SELECT COUNT(*) FROM supplier_invoices WHERE station_id=1 AND source_type='manual' AND source_id=96001 AND is_deleted=0",
            null
        ).use { cursor -> check(cursor.moveToFirst()); assertEquals(1, cursor.getInt(0)) }
    }

    @Test
    fun concurrentIdenticalPaymentAllocationsCreateOneAllocationAndOneIdempotentResult() {
        val invoiceId = SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 96002, "MAN-CONCURRENT-PAYMENT-96002", 100.0, "2026-10-01", null, 1
        )
        val paymentId = createCompletedSupplierPayment(100.0, "CONCURRENT-ALLOCATION")
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val workers = (1..2).map {
            Thread {
                try {
                    start.await(5, TimeUnit.SECONDS)
                    results += SupplierPayablesRepository.allocatePayment(
                        db, 1, supplierId, paymentId, 100.0, 1, invoiceId
                    )
                } catch (error: Throwable) {
                    failures += error
                } finally {
                    done.countDown()
                }
            }.apply { name = "supplier-payment-worker-$it" }
        }
        workers.forEach(Thread::start)
        start.countDown()
        assertTrue("انتهت مهلة اختبار تخصيص الدفعة المتزامن", done.await(10, TimeUnit.SECONDS))
        workers.forEach(Thread::join)
        assertTrue("فشل طلب متزامن لتخصيص الدفعة: $failures", failures.isEmpty())
        assertEquals(2, results.size)
        assertEquals(1, results.count { it.optBoolean("idempotent") })
        assertEquals(1, results.count { !it.optBoolean("idempotent") })
        db.rawQuery(
            "SELECT COUNT(*),COALESCE(SUM(amount),0) FROM supplier_invoice_allocations WHERE payment_id=? AND supplier_invoice_id=? AND status='posted' AND is_deleted=0",
            arrayOf(paymentId.toString(), invoiceId.toString())
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(100.0, cursor.getDouble(1), 0.0001)
        }
    }

    @Test
    fun concurrentIdenticalSupplierPaymentRequestsCreateOnePayment() {
        val invoiceId = SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 96003, "MAN-CONCURRENT-DIRECT-PAYMENT", 10.0, "2026-10-01", null, 1
        )
        db.update(
            "parties",
            ContentValues().apply { put("current_balance", 10.0); put("total_due", 10.0); put("total_payments", 0.0) },
            "id=? AND station_id=1",
            arrayOf(supplierId.toString())
        )
        val roleId = db.rawQuery(
            "SELECT id FROM roles ORDER BY id LIMIT 1",
            null
        ).use { cursor -> check(cursor.moveToFirst()) { "لا يوجد دور اختبار صالح" }; cursor.getLong(0) }
        val actorSuffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val actorId = db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", "supplier-payment-test-$actorSuffix")
            put("password_hash", "test-only")
            put("full_name", "Supplier payment concurrency test")
            put("role_id", roleId)
            put("station_id", 1)
            put("is_deleted", 0)
            put("status", "active")
        })
        val cashBoxId = db.insertOrThrow("cash_boxes", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("box_code", "SP-CONCURRENT-$actorSuffix")
            put("box_name", "Supplier payment concurrency test cash box")
            put("station_id", 1)
            put("opening_balance", 1000.0)
            put("current_balance", 1000.0)
            put("status", "active")
            put("created_by", actorId)
            put("is_deleted", 0)
        })
        db.insertOrThrow("accounts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("account_code", "SP-CASH-$actorSuffix")
            put("account_name", "Supplier payment test cash account")
            put("account_name_ar", "حساب نقدية اختبار دفعة المورد")
            put("account_category", "cash")
            put("level", 1)
            put("account_type", "asset")
            put("normal_balance", "debit")
            put("is_cash_account", 1)
            put("cash_box_id", cashBoxId)
            put("is_active", 1)
            put("is_deleted", 0)
            put("created_by", actorId)
        })
        db.insertOrThrow("accounts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("account_code", "SP-PAYABLE-$actorSuffix")
            put("account_name", "Supplier payment test payable")
            put("account_name_ar", "ذمم الموردين اختبار الدفعة")
            put("account_category", "payable")
            put("level", 1)
            put("account_type", "liability")
            put("normal_balance", "credit")
            put("is_active", 1)
            put("is_deleted", 0)
            put("created_by", actorId)
        })
        val request = JSONObject()
            .put("amount", 10.0).put("supplier_party_id", supplierId).put("cash_box_id", cashBoxId)
            .put("payment_type", "cash").put("payment_method", "cash")
            .put("supplier_invoice_id", invoiceId).put("idempotency_key", "supplier-payment-concurrent-96003")
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val workers = (1..2).map {
            Thread {
                try {
                    start.await(5, TimeUnit.SECONDS)
                    results += helper.addPayment(JSONObject(request.toString()), 1, actorId)
                } catch (error: Throwable) {
                    failures += error
                } finally {
                    done.countDown()
                }
            }.apply { name = "supplier-payment-create-worker-$it" }
        }
        workers.forEach(Thread::start)
        start.countDown()
        assertTrue("انتهت مهلة اختبار إنشاء دفعة المورد المتزامن", done.await(10, TimeUnit.SECONDS))
        workers.forEach(Thread::join)
        assertTrue("فشل طلب متزامن لإنشاء دفعة المورد: $failures", failures.isEmpty())
        assertEquals(2, results.size)
        assertEquals(1, results.toSet().size)
        db.rawQuery(
            "SELECT COUNT(*) FROM payments WHERE station_id=1 AND supplier_party_id=? AND amount=10 AND is_deleted=0",
            arrayOf(supplierId.toString())
        ).use { cursor -> check(cursor.moveToFirst()); assertEquals(1, cursor.getInt(0)) }
        db.rawQuery(
            "SELECT status,resource_id FROM financial_idempotency_keys WHERE operation='supplier_payment' AND station_id=1 AND idempotency_key=?",
            arrayOf("supplier-payment-concurrent-96003")
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals("COMPLETED", cursor.getString(0))
            assertEquals(results.first(), cursor.getLong(1))
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

    @Test
    fun missingOrLegacyUnverifiedTermsDoNotCreateOperationalSupplierMaturities() {
        DebtDueDateRepository.persistPartyTerms(helper,supplierId,1,"")
        val freshId=SupplierPayablesRepository.createPurchaseInvoice(
            db,1,supplierId,"manual",94001,"MAN-94001",80.0,"2026-10-01",null,1
        )
        val legacyId=db.insertOrThrow("supplier_invoices",null,ContentValues().apply {
            put("uuid",UUID.randomUUID().toString());put("station_id",1);put("supplier_id",supplierId)
            put("source_type","manual");put("source_id",94002);put("invoice_number","MAN-94002")
            put("invoice_date","2026-10-01");put("due_date","2026-10-31");put("due_date_basis","2026-10-01")
            put("due_date_source","purchase_creation");put("payment_terms_at_purchase","صافي 30 يوم")
            put("payment_terms_days_at_purchase",30);putNull("payment_terms_source_at_purchase")
            put("total_amount",80.0);put("paid_amount",0.0);put("remaining_amount",80.0)
            put("status","open");put("created_by",1);put("is_deleted",0)
        })
        db.rawQuery("SELECT due_date,due_date_source,payment_terms_days_at_purchase,payment_terms_source_at_purchase FROM supplier_invoices WHERE id=?",arrayOf(freshId.toString())).use { c ->
            check(c.moveToFirst());assertNull(c.getString(0));assertEquals("missing_terms",c.getString(1));assertTrue(c.isNull(2));assertTrue(c.isNull(3))
        }
        val rows=SupplierPayablesRepository.getSupplierInvoices(db,1,supplierId)
        val fresh=(0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getLong("id")==freshId }
        val legacy=(0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getLong("id")==legacyId }
        assertEquals("undated",fresh.getString("maturity_status"));assertEquals("",fresh.getString("due_date"))
        assertEquals("undated",legacy.getString("maturity_status"));assertEquals("",legacy.getString("due_date"))
        assertEquals("2026-10-31",legacy.getString("legacy_due_date"))
    }

    @Test
    fun reversingSupplierPaymentReopensAffectedInvoicesAndIsIdempotent() {
        val invoiceId = SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 93001, "MAN-93001",
            120.0, "2026-10-01", null, 1
        )
        val paymentId = createCompletedSupplierPayment(50.0, "REVERSE")
        val allocation = SupplierPayablesRepository.allocatePayment(
            db, 1, supplierId, paymentId, 50.0, 1, invoiceId
        )
        assertEquals(50.0, allocation.getDouble("allocated_amount"), 0.0001)

        val reversed = SupplierPayablesRepository.reversePaymentAllocations(
            db, 1, supplierId, paymentId, 1, "اختبار عكس دفعة المورد"
        )
        assertFalse(reversed.getBoolean("idempotent"))
        assertEquals(50.0, reversed.getDouble("reversed_amount"), 0.0001)
        assertEquals(1, reversed.getInt("reopened_invoice_count"))

        db.rawQuery(
            "SELECT paid_amount,remaining_amount,status FROM supplier_invoices WHERE id=?",
            arrayOf(invoiceId.toString())
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(0.0, cursor.getDouble(0), 0.0001)
            assertEquals(120.0, cursor.getDouble(1), 0.0001)
            assertEquals("open", cursor.getString(2))
        }
        db.rawQuery(
            "SELECT status,reversal_reason,reversed_by FROM supplier_invoice_allocations WHERE payment_id=?",
            arrayOf(paymentId.toString())
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals("reversed", cursor.getString(0))
            assertEquals("اختبار عكس دفعة المورد", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
        }
        val repeated = SupplierPayablesRepository.reversePaymentAllocations(
            db, 1, supplierId, paymentId, 1, "إعادة المحاولة"
        )
        assertTrue(repeated.getBoolean("idempotent"))
        assertEquals(0.0, repeated.getDouble("reversed_amount"), 0.0001)
    }

    @Test
    fun partialAllocationReversalKeepsOriginalAndReverses300Then200Then500WithoutCashJournal() {
        val invoiceId = SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 93002, "MAN-PARTIAL-REVERSAL", 1000.0, "2026-10-01", null, 1
        )
        val paymentId = createCompletedSupplierPayment(1000.0, "PARTIAL-REVERSAL")
        val allocation = SupplierPayablesRepository.allocatePayment(db, 1, supplierId, paymentId, 1000.0, 1, invoiceId)
        val allocationId = db.rawQuery(
            "SELECT id FROM supplier_invoice_allocations WHERE payment_id=? AND supplier_invoice_id=? AND status='posted' AND is_deleted=0",
            arrayOf(paymentId.toString(), invoiceId.toString())
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        val cashBefore = db.rawQuery("SELECT COUNT(*) FROM cash_movements WHERE reference_type='payment_reversal' AND reference_id=?", arrayOf(paymentId.toString())).use { it.moveToFirst(); it.getInt(0) }
        val journalsBefore = db.rawQuery("SELECT COUNT(*) FROM journal_entries WHERE reference_type='payment' AND reference_id=? AND is_deleted=0", arrayOf(paymentId.toString())).use { it.moveToFirst(); it.getInt(0) }

        listOf(300.0 to "partial-300", 200.0 to "partial-200", 500.0 to "partial-500").forEach { (amount, key) ->
            val result = SupplierPayablesRepository.reversePaymentAllocation(
                db, 1, supplierId, allocationId, amount, 1, "اختبار عكس جزئي $amount", key
            )
            assertFalse(result.getBoolean("idempotent"))
            assertEquals(amount, result.getDouble("reversed_amount"), 0.0001)
        }
        val repeated = SupplierPayablesRepository.reversePaymentAllocation(
            db, 1, supplierId, allocationId, 500.0, 1, "إعادة المحاولة", "partial-500"
        )
        assertTrue(repeated.getBoolean("idempotent"))
        assertTrue(runCatching {
            SupplierPayablesRepository.reversePaymentAllocation(
                db, 1, supplierId, allocationId, 1.0, 1, "عكس زائد", "partial-over"
            )
        }.isFailure)
        db.rawQuery(
            "SELECT status,amount FROM supplier_invoice_allocations WHERE id=?",
            arrayOf(allocationId.toString())
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals("posted", cursor.getString(0))
            assertEquals(1000.0, cursor.getDouble(1), 0.0001)
        }
        db.rawQuery(
            "SELECT COUNT(*),COALESCE(SUM(amount),0) FROM supplier_allocation_reversals WHERE allocation_id=? AND status='posted'",
            arrayOf(allocationId.toString())
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
            assertEquals(1000.0, cursor.getDouble(1), 0.0001)
        }
        db.rawQuery(
            "SELECT paid_amount,remaining_amount,status FROM supplier_invoices WHERE id=?",
            arrayOf(invoiceId.toString())
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(0.0, cursor.getDouble(0), 0.0001)
            assertEquals(1000.0, cursor.getDouble(1), 0.0001)
            assertEquals("open", cursor.getString(2))
        }
        val cashAfter = db.rawQuery("SELECT COUNT(*) FROM cash_movements WHERE reference_type='payment_reversal' AND reference_id=?", arrayOf(paymentId.toString())).use { it.moveToFirst(); it.getInt(0) }
        val journalsAfter = db.rawQuery("SELECT COUNT(*) FROM journal_entries WHERE reference_type='payment' AND reference_id=? AND is_deleted=0", arrayOf(paymentId.toString())).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(cashBefore, cashAfter)
        assertEquals(journalsBefore, journalsAfter)
        assertEquals(1000.0, allocation.getDouble("allocated_amount"), 0.0001)
    }

    @Test
    fun partialAllocationReversalIdempotencyKeyCannotBeReusedForAnotherAllocationOrAmount() {
        val firstInvoice = SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 93003, "MAN-IDEMPOTENCY-A", 100.0, "2026-10-01", null, 1
        )
        val secondInvoice = SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 93004, "MAN-IDEMPOTENCY-B", 100.0, "2026-10-01", null, 1
        )
        val firstPayment = createCompletedSupplierPayment(100.0, "IDEMPOTENCY-A")
        val secondPayment = createCompletedSupplierPayment(100.0, "IDEMPOTENCY-B")
        SupplierPayablesRepository.allocatePayment(db, 1, supplierId, firstPayment, 100.0, 1, firstInvoice)
        SupplierPayablesRepository.allocatePayment(db, 1, supplierId, secondPayment, 100.0, 1, secondInvoice)
        fun allocationId(paymentId: Long): Long = db.rawQuery(
            "SELECT id FROM supplier_invoice_allocations WHERE payment_id=? AND status='posted' AND is_deleted=0",
            arrayOf(paymentId.toString())
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        val firstAllocation = allocationId(firstPayment)
        val secondAllocation = allocationId(secondPayment)

        val original = SupplierPayablesRepository.reversePaymentAllocation(
            db, 1, supplierId, firstAllocation, 25.0, 1, "عكس أول", "shared-reversal-key"
        )
        assertFalse(original.getBoolean("idempotent"))
        val retry = SupplierPayablesRepository.reversePaymentAllocation(
            db, 1, supplierId, firstAllocation, 25.0, 1, "إعادة المحاولة", "shared-reversal-key"
        )
        assertTrue(retry.getBoolean("idempotent"))
        assertEquals(25.0, retry.getDouble("reversed_amount"), 0.0001)

        assertTrue(runCatching {
            SupplierPayablesRepository.reversePaymentAllocation(
                db, 1, supplierId, secondAllocation, 25.0, 1, "إعادة استخدام المفتاح", "shared-reversal-key"
            )
        }.isFailure)
        assertTrue(runCatching {
            SupplierPayablesRepository.reversePaymentAllocation(
                db, 1, supplierId, firstAllocation, 20.0, 1, "تغيير مبلغ المفتاح", "shared-reversal-key"
            )
        }.isFailure)
        db.rawQuery(
            "SELECT COUNT(*),COALESCE(SUM(amount),0) FROM supplier_allocation_reversals WHERE station_id=1 AND idempotency_key='shared-reversal-key' AND status='posted'",
            null
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(25.0, cursor.getDouble(1), 0.0001)
        }
    }

    @Test
    fun currentPaymentExcessOffsetsAreReportedSeparatelyPerCurrency() {
        SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 94001, "MAN-94001",
            40.0, "2026-10-01", 3L, 1
        )
        val paymentId = createCompletedSupplierPayment(70.0, "LEGACY-CURRENCY", 3L)
        val allocation = SupplierPayablesRepository.allocatePayment(
            db, 1, supplierId, paymentId, 70.0, 1
        )
        assertEquals(40.0, allocation.getDouble("allocated_amount"), 0.0001)
        assertEquals(30.0, allocation.getDouble("unapplied_amount"), 0.0001)

        val aging = SupplierPayablesRepository.getSupplierAging(db, 1, supplierId, "2026-11-05")
        val offsets = aging.getJSONArray("legacy_payment_offsets_by_currency")
        assertEquals(1, offsets.length())
        assertEquals(3L, offsets.getJSONObject(0).getLong("currency_id"))
        assertEquals(30.0, offsets.getJSONObject(0).getDouble("amount"), 0.0001)
        assertEquals("current_payment_excess_after_invoice_allocation",
            offsets.getJSONObject(0).getString("classification"))
        assertFalse(offsets.getJSONObject(0).getBoolean("legacy_balance_offset_not_invoice_allocated"))
        assertEquals(0.0,
            aging.getDouble("unapplied_legacy_payments_without_currency"), 0.0001)
    }

    @Test
    fun preMigrationUnappliedSupplierPaymentsKeepLegacyClassificationPerCurrency() {
        val paymentId = createCompletedSupplierPayment(25.0, "LEGACY-UNAPPLIED", 3L)
        db.insertOrThrow("supplier_invoice_allocations", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("station_id", 1)
            put("supplier_id", supplierId)
            putNull("supplier_invoice_id")
            put("payment_id", paymentId)
            put("allocation_kind", "unapplied_legacy")
            put("amount", 25.0)
            put("currency_id", 3L)
            put("status", "posted")
            put("is_deleted", 0)
            put("allocated_at", "2026-11-05")
            put("created_by", 1)
            // Deliberately omit unapplied_reason to represent a row created before v54.
        })

        val offsets = SupplierPayablesRepository.getSupplierAging(db, 1, supplierId, "2026-11-05")
            .getJSONArray("legacy_payment_offsets_by_currency")
        assertEquals(1, offsets.length())
        assertEquals(3L, offsets.getJSONObject(0).getLong("currency_id"))
        assertEquals(25.0, offsets.getJSONObject(0).getDouble("amount"), 0.0001)
        assertEquals("historical_or_unmatched_supplier_balance",
            offsets.getJSONObject(0).getString("classification"))
        assertTrue(offsets.getJSONObject(0).getBoolean("legacy_balance_offset_not_invoice_allocated"))
    }

    @Test
    fun supplierOutstandingBalanceIsNotMultipliedByTheNumberOfInvoices() {
        SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 95001, "MAN-95001",
            100.0, "2026-11-01", null, 1
        )
        SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 95002, "MAN-95002",
            200.0, "2026-11-02", null, 1
        )
        assertEquals(1, db.update("parties", ContentValues().apply {
            put("current_balance", 300.0)
            put("total_due", 300.0)
        }, "id=? AND station_id=?", arrayOf(supplierId.toString(), "1")))

        val result = helper.getSupplierOutstandingBalance(supplierId, 1)
        assertEquals(300.0, result.getDouble("current_balance"), 0.0001)
        assertEquals(300.0, result.getDouble("total_due"), 0.0001)
        val currencyTotals = result.getJSONObject("invoice_aging").getJSONArray("currency_totals")
        assertEquals(1, currencyTotals.length())
        assertEquals(300.0, currencyTotals.getJSONObject(0).getDouble("open_balance"), 0.0001)
    }

    @Test
    fun dashboardSupplierDebtDoesNotFanOutThreeInvoicesAndTwoPaymentsAcrossStations() {
        val invoices = listOf(
            SupplierPayablesRepository.createPurchaseInvoice(
                db, 1, supplierId, "manual", 95101, "DASH-MAN-95101", 100.0, "2026-11-01", null, 1
            ),
            SupplierPayablesRepository.createPurchaseInvoice(
                db, 1, supplierId, "manual", 95102, "DASH-MAN-95102", 200.0, "2026-11-02", null, 1
            ),
            SupplierPayablesRepository.createPurchaseInvoice(
                db, 1, supplierId, "manual", 95103, "DASH-MAN-95103", 300.0, "2026-11-03", null, 1
            )
        )
        val firstPayment = createCompletedSupplierPayment(150.0, "DASH-1")
        val secondPayment = createCompletedSupplierPayment(50.0, "DASH-2")
        SupplierPayablesRepository.allocatePayment(db, 1, supplierId, firstPayment, 150.0, 1)
        SupplierPayablesRepository.allocatePayment(db, 1, supplierId, secondPayment, 50.0, 1)

        val remainingInvoices = SupplierPayablesRepository.getSupplierAging(db, 1, supplierId, "2026-11-05")
            .getJSONArray("currency_totals")
        assertEquals(1, remainingInvoices.length())
        assertEquals(400.0, remainingInvoices.getJSONObject(0).getDouble("open_balance"), 0.0001)
        assertEquals(3, db.rawQuery(
            "SELECT COUNT(*) FROM supplier_invoice_allocations WHERE station_id=1 AND status='posted' AND is_deleted=0 AND supplier_invoice_id IN (?,?,?)",
            invoices.map { it.toString() }.toTypedArray()
        ).use { check(it.moveToFirst()); it.getInt(0) })

        val partyTypeId = db.rawQuery(
            "SELECT id FROM party_types WHERE type_code='SUPPLIER' AND is_deleted=0 LIMIT 1", null
        ).use { check(it.moveToFirst()); it.getLong(0) }
        val sameStationSupplier = helper.insertParty(
            JSONObject().put("party_type_id", partyTypeId).put("party_type", "supplier")
                .put("commercial_name", "Second dashboard supplier").put("commercial_name_ar", "مورد ثانٍ")
                .put("is_active", true), 1
        )
        val otherStationSupplier = helper.insertParty(
            JSONObject().put("party_type_id", partyTypeId).put("party_type", "supplier")
                .put("commercial_name", "Other-station dashboard supplier").put("commercial_name_ar", "مورد محطة أخرى")
                .put("is_active", true), 2
        )
        assertEquals(1, db.update("parties", ContentValues().apply {
            put("current_balance", 400.0); put("total_due", 400.0)
        }, "id=? AND station_id=?", arrayOf(supplierId.toString(), "1")))
        assertEquals(1, db.update("parties", ContentValues().apply {
            put("current_balance", 25.0); put("total_due", 25.0)
        }, "id=? AND station_id=?", arrayOf(sameStationSupplier.toString(), "1")))
        assertEquals(1, db.update("parties", ContentValues().apply {
            put("current_balance", 900.0); put("total_due", 900.0)
        }, "id=? AND station_id=?", arrayOf(otherStationSupplier.toString(), "2")))

        // POL-09/POL-11 remain pending owner approval. This one-currency fixture only
        // proves the current dashboard query does not multiply a supplier by invoice/payment rows.
        val dashboard = helper.getDashboardStatsForDate(1, JSONObject(), "2026-11-05")
        assertEquals(425.0, dashboard.getDouble("supplier_debts"), 0.0001)
        assertEquals(400.0, dashboard.getDouble("total_supplier_payables"), 0.0001)
        assertEquals(400.0, dashboard.getJSONObject("supplier_payables_summary").getDouble("total_outstanding"), 0.0001)
    }

    @Test
    fun paidAndCancelledSupplierInvoicesAreFullyExcludedFromPendingTasks() {
        val openId = SupplierPayablesRepository.createPurchaseInvoice(db, 1, supplierId, "manual", 96001, "OPEN-96001", 100.0, "2026-11-01", null, 1)
        val paidId = SupplierPayablesRepository.createPurchaseInvoice(db, 1, supplierId, "manual", 96002, "PAID-96002", 200.0, "2026-11-01", null, 1)
        val cancelledId = SupplierPayablesRepository.createPurchaseInvoice(db, 1, supplierId, "manual", 96003, "CANCEL-96003", 300.0, "2026-11-01", null, 1)
        assertEquals(1, db.update("supplier_invoices", ContentValues().apply {
            put("status", "paid"); put("paid_amount", 200.0); put("remaining_amount", 0.0)
        }, "id=?", arrayOf(paidId.toString())))
        assertEquals(1, db.update("supplier_invoices", ContentValues().apply {
            put("status", "cancelled"); put("paid_amount", 0.0); put("remaining_amount", 300.0)
        }, "id=?", arrayOf(cancelledId.toString())))

        val rows = helper.getPendingTasks(1, JSONObject().put("search", "960"))
        val supplierRows = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .filter { it.optString("source_kind") == "supplier_invoice" }
        assertEquals(1, supplierRows.size)
        assertEquals(openId, supplierRows.single().getLong("source_id"))
        assertEquals("OPEN-96001", supplierRows.single().getString("reference"))
    }

    private fun createFinanceActor(): Long {
        val roleId = db.rawQuery("SELECT id FROM roles ORDER BY id LIMIT 1", null).use {
            check(it.moveToFirst()); it.getLong(0)
        }
        return db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", "supplier-flow-${UUID.randomUUID().toString().take(10)}")
            put("password_hash", "test-only")
            put("full_name", "مستخدم اختبار دورة المورد")
            put("role_id", roleId)
            put("station_id", 1)
        })
    }

    private fun createCashBoxAndPostingAccounts(actorId: Long): Long {
        val cashBoxId = db.insertOrThrow("cash_boxes", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("box_code", "SP-CASH-${UUID.randomUUID().toString().take(8)}")
            put("box_name", "Supplier workflow test cash")
            put("box_name_ar", "صندوق اختبار دورة المورد")
            put("station_id", 1)
            put("opening_balance", 10000.0)
            put("current_balance", 10000.0)
            put("maximum_balance", 50000.0)
            put("status", "active")
            put("is_deleted", 0)
            put("created_by", actorId)
        })
        db.insertOrThrow("accounts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("account_code", "SP-CASH-ACC-${UUID.randomUUID().toString().take(8)}")
            put("account_name", "Supplier test cash account")
            put("account_name_ar", "حساب صندوق اختبار المورد")
            put("level", 1)
            put("account_type", "asset")
            put("account_category", "cash")
            put("normal_balance", "debit")
            put("is_cash_account", 1)
            put("cash_box_id", cashBoxId)
            put("is_active", 1)
            put("is_deleted", 0)
            put("created_by", actorId)
        })

        val inventoryAccountExists = db.rawQuery(
            """SELECT 1 FROM accounts
               WHERE account_type='asset' AND is_active=1 AND is_deleted=0
                 AND (lower(COALESCE(account_category,'')) LIKE '%inventory%'
                      OR lower(COALESCE(account_category,'')) LIKE '%stock%'
                      OR COALESCE(account_name_ar,'') LIKE '%المخزون%'
                      OR COALESCE(account_name_ar,'') LIKE '%البضاعة%'
                      OR COALESCE(account_name,'') LIKE '%Inventory%')
               LIMIT 1""", null
        ).use { it.moveToFirst() }
        if (!inventoryAccountExists) {
            db.insertOrThrow("accounts", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("account_code", "SP-INV-${UUID.randomUUID().toString().take(8)}")
                put("account_name", "Supplier workflow inventory")
                put("account_name_ar", "مخزون اختبار المورد")
                put("level", 1)
                put("account_type", "asset")
                put("account_category", "inventory")
                put("normal_balance", "debit")
                put("is_active", 1)
                put("is_deleted", 0)
                put("created_by", actorId)
            })
        }
        val payableAccountExists = db.rawQuery(
            """SELECT 1 FROM accounts WHERE account_type='liability'
               AND is_active=1 AND is_deleted=0
               AND (account_code='2101' OR lower(COALESCE(account_category,'')) LIKE '%pay%'
                    OR lower(COALESCE(account_name,'')) LIKE '%payable%'
                    OR lower(COALESCE(account_name,'')) LIKE '%supplier%'
                    OR COALESCE(account_name_ar,'') LIKE '%مورد%'
                    OR COALESCE(account_name_ar,'') LIKE '%دائن%')
               LIMIT 1""", null
        ).use { it.moveToFirst() }
        if (!payableAccountExists) {
            db.insertOrThrow("accounts", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("account_code", "SP-AP-${UUID.randomUUID().toString().take(8)}")
                put("account_name", "Supplier accounts payable")
                put("account_name_ar", "ذمم الموردين")
                put("level", 1)
                put("account_type", "liability")
                put("account_category", "accounts_payable")
                put("normal_balance", "credit")
                put("is_active", 1)
                put("is_deleted", 0)
                put("created_by", actorId)
            })
        }
        return cashBoxId
    }

    private fun createFuelTank(actorId: Long): Pair<Long, Long> {
        val fuelTypeId = db.rawQuery(
            "SELECT id FROM fuel_types WHERE is_active=1 AND is_deleted=0 ORDER BY id LIMIT 1", null
        ).use { c ->
            if (c.moveToFirst()) c.getLong(0) else db.insertOrThrow("fuel_types", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("fuel_code", "SPF-${UUID.randomUUID().toString().take(6)}")
                put("fuel_name", "Supplier workflow diesel")
                put("fuel_name_ar", "ديزل اختبار المورد")
                put("is_active", 1)
                put("is_deleted", 0)
                put("created_by", actorId)
            })
        }
        val tankId = db.insertOrThrow("tanks", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("tank_code", "SPT-${UUID.randomUUID().toString().take(8)}")
            put("tank_name", "Supplier workflow test tank")
            put("tank_name_ar", "خزان اختبار دورة المورد")
            put("station_id", 1)
            put("fuel_type_id", fuelTypeId)
            put("capacity_liters", 1000.0)
            put("current_quantity", 0.0)
            put("status", "active")
            put("is_deleted", 0)
            put("created_by", actorId)
        })
        return tankId to fuelTypeId
    }

    private fun assertBalancedSourceJournal(referenceType: String, sourceId: Long, expectedAmount: Double) {
        val journal = db.rawQuery(
            """SELECT id,total_debit,total_credit,status,is_balanced
               FROM journal_entries
               WHERE station_id=1 AND reference_type=? AND reference_id=? AND is_deleted=0""",
            arrayOf(referenceType, sourceId.toString())
        ).use { c ->
            check(c.moveToFirst()) { "قيد المصدر المالي غير موجود: $referenceType/$sourceId" }
            val captured = Triple(c.getLong(0), c.getDouble(1), c.getDouble(2))
            assertEquals("posted", c.getString(3))
            assertEquals(1, c.getInt(4))
            assertFalse("لا يجوز تكرار القيد للمصدر نفسه", c.moveToNext())
            captured
        }
        assertEquals(expectedAmount, journal.second, 0.01)
        assertEquals(expectedAmount, journal.third, 0.01)
        db.rawQuery(
            "SELECT COALESCE(SUM(debit),0),COALESCE(SUM(credit),0),COUNT(*) FROM journal_entry_items WHERE journal_entry_id=?",
            arrayOf(journal.first.toString())
        ).use { c ->
            check(c.moveToFirst())
            assertEquals(expectedAmount, c.getDouble(0), 0.01)
            assertEquals(expectedAmount, c.getDouble(1), 0.01)
            assertEquals(2, c.getInt(2))
        }
    }

    private fun assertSupplierInvoice(
        sourceType: String,
        sourceId: Long,
        expectedTotal: Double,
        expectedStatus: String,
        expectedPaid: Double,
        expectedRemaining: Double
    ): Long {
        return db.rawQuery(
            """SELECT id,total_amount,paid_amount,remaining_amount,status
               FROM supplier_invoices
               WHERE station_id=1 AND source_type=? AND source_id=? AND is_deleted=0""",
            arrayOf(sourceType, sourceId.toString())
        ).use { c ->
            check(c.moveToFirst()) { "فاتورة المورد غير مرتبطة بمستند المصدر $sourceType/$sourceId" }
            val id = c.getLong(0)
            assertEquals(expectedTotal, c.getDouble(1), 0.01)
            assertEquals(expectedPaid, c.getDouble(2), 0.01)
            assertEquals(expectedRemaining, c.getDouble(3), 0.01)
            assertEquals(expectedStatus, c.getString(4))
            assertFalse("يجب أن توجد فاتورة واحدة فقط لكل مستند مصدر", c.moveToNext())
            id
        }
    }


    @Test
    fun supplierNetSevenUsesSupplierScopedImmutableSnapshot() {
        DebtDueDateRepository.persistPartyTerms(helper, supplierId, 1, "صافي 7 يوم")
        val net7 = SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 97001, "SUP-NET7", 70.0, "2026-11-01", null, 1
        )
        db.rawQuery(
            """SELECT i.due_date,i.payment_terms_days_at_purchase,i.payment_terms_at_purchase,
                      i.due_date_basis,i.payment_terms_source_at_purchase,t.code,t.applies_to
               FROM supplier_invoices i
               JOIN payment_terms t ON t.id=i.payment_terms_id_at_purchase
               WHERE i.id=?""",
            arrayOf(net7.toString())
        ).use { c ->
            check(c.moveToFirst())
            assertEquals("2026-11-08", c.getString(0))
            assertEquals(7, c.getInt(1))
            assertEquals("صافي 7 يوم", c.getString(2))
            assertEquals("2026-11-01", c.getString(3))
            assertEquals("explicit_text", c.getString(4))
            assertEquals("SUPPLIER_NET_7", c.getString(5))
            assertEquals("supplier", c.getString(6))
        }
        DebtDueDateRepository.persistPartyTerms(helper, supplierId, 1, "صافي 30 يوم")
        db.rawQuery(
            "SELECT due_date,payment_terms_days_at_purchase FROM supplier_invoices WHERE id=?",
            arrayOf(net7.toString())
        ).use { c ->
            check(c.moveToFirst())
            assertEquals("2026-11-08", c.getString(0))
            assertEquals(7, c.getInt(1))
        }
    }

    @Test
    fun supplierNetThirtyUsesSupplierScopedImmutableSnapshot() {
        DebtDueDateRepository.persistPartyTerms(helper, supplierId, 1, "صافي 30 يوم")
        val net30 = SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 97002, "SUP-NET30", 300.0, "2026-11-01", null, 1
        )
        db.rawQuery(
            """SELECT i.due_date,i.payment_terms_days_at_purchase,i.payment_terms_at_purchase,
                      i.due_date_basis,i.payment_terms_source_at_purchase,t.code,t.applies_to
               FROM supplier_invoices i
               JOIN payment_terms t ON t.id=i.payment_terms_id_at_purchase
               WHERE i.id=?""",
            arrayOf(net30.toString())
        ).use { c ->
            check(c.moveToFirst())
            assertEquals("2026-12-01", c.getString(0))
            assertEquals(30, c.getInt(1))
            assertEquals("صافي 30 يوم", c.getString(2))
            assertEquals("2026-11-01", c.getString(3))
            assertEquals("explicit_text", c.getString(4))
            assertEquals("SUPPLIER_NET_30", c.getString(5))
            assertEquals("supplier", c.getString(6))
        }
        DebtDueDateRepository.persistPartyTerms(helper, supplierId, 1, "صافي 7 يوم")
        db.rawQuery(
            "SELECT due_date,payment_terms_days_at_purchase FROM supplier_invoices WHERE id=?",
            arrayOf(net30.toString())
        ).use { c ->
            check(c.moveToFirst())
            assertEquals("2026-12-01", c.getString(0))
            assertEquals(30, c.getInt(1))
        }
    }

    @Test
    fun supplierInvoiceTasksArePersistedIdempotentAndClosedAfterPaymentOrCancellation() {
        val paidInvoiceId = SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 97101, "TASK-PAID", 100.0, "2026-11-01", null, 1
        )
        val taskId = db.rawQuery(
            """SELECT id FROM supplier_invoice_tasks
               WHERE station_id=1 AND supplier_id=? AND supplier_invoice_id=? AND is_deleted=0""",
            arrayOf(supplierId.toString(), paidInvoiceId.toString())
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }

        repeat(3) { helper.getPendingTasks(1, JSONObject().put("search", "TASK-PAID")) }
        val duplicateCount = db.rawQuery(
            """SELECT COUNT(*) FROM supplier_invoice_tasks
               WHERE station_id=1 AND supplier_id=? AND supplier_invoice_id=? AND is_deleted=0""",
            arrayOf(supplierId.toString(), paidInvoiceId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(1, duplicateCount)

        val projected = helper.getPendingTasks(1, JSONObject().put("search", "TASK-PAID"))
        val supplierTask = (0 until projected.length()).mapNotNull { projected.optJSONObject(it) }
            .single { it.optString("source_kind") == "supplier_invoice" }
        assertEquals(paidInvoiceId, supplierTask.getLong("source_id"))
        assertEquals(taskId, db.rawQuery(
            """SELECT id FROM supplier_invoice_tasks
               WHERE supplier_invoice_id=? AND station_id=1 AND is_resolved=0""",
            arrayOf(paidInvoiceId.toString())
        ).use { c -> check(c.moveToFirst()); c.getLong(0) })

        val partialPaymentId = createCompletedSupplierPayment(25.0, "TASK-PARTIAL")
        SupplierPayablesRepository.allocatePayment(db, 1, supplierId, partialPaymentId, 25.0, 1, paidInvoiceId)
        db.rawQuery(
            "SELECT is_resolved,status,resolved_at,amount FROM supplier_invoice_tasks WHERE id=?",
            arrayOf(taskId.toString())
        ).use { c ->
            check(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            // Task status is date-derived (overdue / near due / unpaid / review-needed).
            // The lifecycle invariant here is that a partial allocation must not resolve the task.
            assertTrue(
                "يجب أن تبقى مهمة الفاتورة مفتوحة بعد السداد الجزئي",
                c.getString(1) in setOf("متأخرة", "قريبة من الاستحقاق", "غير مدفوعة", "بحاجة مراجعة")
            )
            assertTrue(c.isNull(2))
            assertEquals(75.0, c.getDouble(3), 0.0001)
        }
        val finalPaymentId = createCompletedSupplierPayment(75.0, "TASK-PAID")
        SupplierPayablesRepository.allocatePayment(db, 1, supplierId, finalPaymentId, 75.0, 1, paidInvoiceId)
        db.rawQuery(
            "SELECT is_resolved,status,resolved_at,amount FROM supplier_invoice_tasks WHERE id=?",
            arrayOf(taskId.toString())
        ).use { c ->
            check(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals("مكتملة", c.getString(1))
            assertTrue(!c.isNull(2))
            assertEquals(0.0, c.getDouble(3), 0.0001)
        }
        val hiddenPaid = helper.getPendingTasks(1, JSONObject().put("search", "TASK-PAID"))
        assertEquals(0, (0 until hiddenPaid.length()).count {
            hiddenPaid.optJSONObject(it)?.optString("source_kind") == "supplier_invoice"
        })
        val includeResolved = helper.getPendingTasks(
            1, JSONObject().put("search", "TASK-PAID").put("include_resolved", true)
        )
        assertEquals(1, (0 until includeResolved.length()).count {
            includeResolved.optJSONObject(it)?.optString("source_kind") == "supplier_invoice"
        })

        val cancelledInvoiceId = SupplierPayablesRepository.createPurchaseInvoice(
            db, 1, supplierId, "manual", 97102, "TASK-CANCEL", 75.0, "2026-11-01", null, 1
        )
        val cancelledTaskId = db.rawQuery(
            "SELECT id FROM supplier_invoice_tasks WHERE supplier_invoice_id=? AND station_id=1",
            arrayOf(cancelledInvoiceId.toString())
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }
        assertEquals(1, db.update(
            "supplier_invoices",
            ContentValues().apply { put("status", "cancelled") },
            "id=? AND station_id=1",
            arrayOf(cancelledInvoiceId.toString())
        ))
        helper.getPendingTasks(1, JSONObject().put("search", "TASK-CANCEL"))
        db.rawQuery(
            "SELECT is_resolved,status FROM supplier_invoice_tasks WHERE id=?",
            arrayOf(cancelledTaskId.toString())
        ).use { c ->
            check(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals("مكتملة", c.getString(1))
        }
    }

    @Test
    fun supplierPurchaseInvoiceRejectsMissingSourceAndNonSupplierParty() {
        val missingFuelSource = runCatching {
            SupplierPayablesRepository.createPurchaseInvoice(
                db, 1, supplierId, "fuel_refill", 97991, "MISSING-FUEL", 50.0, "2026-11-01", null, 1
            )
        }.isFailure
        val missingProductSource = runCatching {
            SupplierPayablesRepository.createPurchaseInvoice(
                db, 1, supplierId, "product_purchase", 97992, "MISSING-PRODUCT", 50.0, "2026-11-01", null, 1
            )
        }.isFailure
        assertTrue("يجب رفض فاتورة وقود بلا مستند توريد", missingFuelSource)
        assertTrue("يجب رفض فاتورة منتجات بلا مستند شراء", missingProductSource)

        val customerTypeId = db.rawQuery(
            "SELECT id FROM party_types WHERE type_code='INDIVIDUAL' AND is_deleted=0 LIMIT 1", null
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }
        val customerId = helper.insertParty(
            JSONObject()
                .put("party_type_id", customerTypeId)
                .put("party_type", "customer")
                .put("commercial_name", "Supplier validation customer")
                .put("commercial_name_ar", "عميل لا يجوز اعتماده مورداً")
                .put("is_active", true),
            1
        )
        val invalidParty = runCatching {
            SupplierPayablesRepository.createPurchaseInvoice(
                db, 1, customerId, "manual", 97993, "INVALID-SUPPLIER", 50.0, "2026-11-01", null, 1
            )
        }.isFailure
        assertTrue("يجب رفض طرف غير مصنف مورداً", invalidParty)
        assertEquals(0, db.rawQuery(
            "SELECT COUNT(*) FROM supplier_invoices WHERE station_id=1 AND source_id IN (97991,97992,97993)",
            null
        ).use { c -> c.moveToFirst(); c.getInt(0) })
    }

    @Test
    fun fuelCashAndCreditSuppliesCreateSourceInvoicesBalancedJournalsAndAreIdempotent() {
        val actorId = createFinanceActor()
        val cashBoxId = createCashBoxAndPostingAccounts(actorId)
        val (tankId, fuelTypeId) = createFuelTank(actorId)
        DebtDueDateRepository.persistPartyTerms(helper, supplierId, 1, "صافي 7 يوم")

        val cashPayload = JSONObject()
            .put("supplier_id", supplierId)
            .put("tank_id", tankId)
            .put("fuel_type_id", fuelTypeId)
            .put("ordered_quantity", 10.0)
            .put("unit_price", 5.0)
            .put("transport_cost", 0.0)
            .put("discount", 0.0)
            .put("tax_amount", 0.0)
            .put("payment_method", "cash")
            .put("cash_box_id", cashBoxId)
            .put("invoice_number", "FUEL-CASH-INT")
            .put("invoice_date", "2026-11-01")
            .put("order_date", "2026-11-01")
            .put("idempotency_key", "fuel-cash-${UUID.randomUUID()}")

        val cashResult = helper.saveTankRefillWithPayment(cashPayload, 1, actorId)
        val cashRefillId = cashResult.getLong("refill_id")
        assertEquals(50.0, cashResult.getDouble("net_amount"), 0.01)
        val cashInvoiceId = assertSupplierInvoice(
            "fuel_refill", cashRefillId, 50.0, "paid", 50.0, 0.0
        )
        assertBalancedSourceJournal("fuel_supply", cashRefillId, 50.0)
        val retryCash = helper.saveTankRefillWithPayment(cashPayload, 1, actorId)
        assertTrue(retryCash.optBoolean("idempotent"))
        assertEquals(cashRefillId, retryCash.getLong("refill_id"))

        val creditPayload = JSONObject(cashPayload.toString())
            .put("payment_method", "credit")
            .put("cash_box_id", 0)
            .put("unit_price", 7.0)
            .put("invoice_number", "FUEL-CREDIT-INT")
            .put("idempotency_key", "fuel-credit-${UUID.randomUUID()}")
        val creditResult = helper.saveTankRefillWithPayment(creditPayload, 1, actorId)
        val creditRefillId = creditResult.getLong("refill_id")
        assertEquals(70.0, creditResult.getDouble("net_amount"), 0.01)
        val creditInvoiceId = assertSupplierInvoice(
            "fuel_refill", creditRefillId, 70.0, "open", 0.0, 70.0
        )
        assertBalancedSourceJournal("fuel_supply", creditRefillId, 70.0)
        val retryCredit = helper.saveTankRefillWithPayment(creditPayload, 1, actorId)
        assertTrue(retryCredit.optBoolean("idempotent"))
        assertEquals(creditRefillId, retryCredit.getLong("refill_id"))

        assertEquals(2, db.rawQuery(
            "SELECT COUNT(*) FROM tank_refills WHERE station_id=1 AND supplier_id=? AND is_deleted=0",
            arrayOf(supplierId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        assertEquals(2, db.rawQuery(
            "SELECT COUNT(*) FROM supplier_invoices WHERE station_id=1 AND source_type='fuel_refill' AND source_id IN (?,?) AND is_deleted=0",
            arrayOf(cashRefillId.toString(), creditRefillId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        assertEquals(20.0, db.rawQuery(
            "SELECT current_quantity FROM tanks WHERE id=? AND station_id=1",
            arrayOf(tankId.toString())
        ).use { c -> check(c.moveToFirst()); c.getDouble(0) }, 0.0001)
        assertEquals(cashInvoiceId, db.rawQuery(
            "SELECT id FROM supplier_invoices WHERE source_type='fuel_refill' AND source_id=?",
            arrayOf(cashRefillId.toString())
        ).use { c -> check(c.moveToFirst()); c.getLong(0) })
        assertTrue(creditInvoiceId > 0L)
        db.rawQuery(
            "SELECT current_balance,total_due,total_purchases FROM parties WHERE id=? AND station_id=1",
            arrayOf(supplierId.toString())
        ).use { c ->
            check(c.moveToFirst())
            assertEquals(70.0, c.getDouble(0), 0.01)
            assertEquals(70.0, c.getDouble(1), 0.01)
            assertEquals(120.0, c.getDouble(2), 0.01)
        }
        val sameKeyDifferentAmount = JSONObject(cashPayload.toString()).put("unit_price", 6.0)
        assertTrue(runCatching {
            helper.saveTankRefillWithPayment(sameKeyDifferentAmount, 1, actorId)
        }.isFailure)
    }

    @Test
    fun productCashAndCreditPurchasesCreateUniqueSupplierInvoicesAndExcludeInternalTransfers() {
        val actorId = createFinanceActor()
        val cashBoxId = createCashBoxAndPostingAccounts(actorId)
        val categoryId = db.rawQuery(
            "SELECT id FROM product_categories WHERE is_deleted=0 ORDER BY id LIMIT 1", null
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }
        val unitId = db.rawQuery("SELECT id FROM units ORDER BY id LIMIT 1", null)
            .use { c -> check(c.moveToFirst()); c.getLong(0) }
        val productId = helper.insertProduct(
            JSONObject()
                .put("product_name", "Supplier purchase integration product")
                .put("product_name_ar", "منتج اختبار مشتريات المورد")
                .put("category_id", categoryId)
                .put("unit_id", unitId)
                .put("purchase_price", 5.0)
                .put("sale_price", 8.0)
                .put("quantity", 0.0)
                .put("minimum_stock", 0.0),
            1, actorId
        )
        val warehouseId = helper.ensureOperationalWarehouse(1)

        val creditPayload = JSONObject()
            .put("movement_type", "in")
            .put("movement_subtype", "in")
            .put("financial_purchase", true)
            .put("payment_method", "credit")
            .put("supplier_id", supplierId)
            .put("product_id", productId)
            .put("warehouse_id", warehouseId)
            .put("quantity", 10.0)
            .put("unit_cost", 5.0)
            .put("movement_code", "SP-PROD-CREDIT-${UUID.randomUUID().toString().take(8)}")
            .put("invoice_number", "PROD-CREDIT-INT")
            .put("invoice_date", "2026-11-02")
            .put("idempotency_key", "product-credit-${UUID.randomUUID()}")

        val creditMovementId = helper.addStockMovement(creditPayload, 1, actorId)
        val creditInvoiceId = assertSupplierInvoice(
            "product_purchase", creditMovementId, 50.0, "open", 0.0, 50.0
        )
        assertBalancedSourceJournal("product_purchase", creditMovementId, 50.0)
        val duplicateCreditId = helper.addStockMovement(creditPayload, 1, actorId)
        assertEquals(creditMovementId, duplicateCreditId)
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM inventory_movements WHERE id=? AND station_id=1",
            arrayOf(creditMovementId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) })

        val cashPayload = JSONObject()
            .put("movement_type", "in")
            .put("movement_subtype", "in")
            .put("financial_purchase", true)
            .put("payment_method", "cash")
            .put("cash_box_id", cashBoxId)
            .put("supplier_id", supplierId)
            .put("product_id", productId)
            .put("warehouse_id", warehouseId)
            .put("quantity", 5.0)
            .put("unit_cost", 6.0)
            .put("movement_code", "SP-PROD-CASH-${UUID.randomUUID().toString().take(8)}")
            .put("invoice_number", "PROD-CASH-INT")
            .put("invoice_date", "2026-11-03")
            .put("idempotency_key", "product-cash-${UUID.randomUUID()}")

        val cashMovementId = helper.addStockMovement(cashPayload, 1, actorId)
        val cashInvoiceId = assertSupplierInvoice(
            "product_purchase", cashMovementId, 30.0, "paid", 30.0, 0.0
        )
        assertBalancedSourceJournal("product_purchase", cashMovementId, 30.0)
        val duplicateCashId = helper.addStockMovement(cashPayload, 1, actorId)
        assertEquals(cashMovementId, duplicateCashId)
        assertTrue(creditInvoiceId != cashInvoiceId)
        db.rawQuery(
            "SELECT current_balance,total_due,total_purchases FROM parties WHERE id=? AND station_id=1",
            arrayOf(supplierId.toString())
        ).use { c ->
            check(c.moveToFirst())
            assertEquals(50.0, c.getDouble(0), 0.01)
            assertEquals(50.0, c.getDouble(1), 0.01)
            assertEquals(80.0, c.getDouble(2), 0.01)
        }

        val changedPurchase = JSONObject(creditPayload.toString()).put("unit_cost", 7.0)
        assertTrue(runCatching { helper.addStockMovement(changedPurchase, 1, actorId) }.isFailure)

        val secondWarehouseId = db.insertOrThrow("warehouses", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("station_id", 1)
            put("warehouse_name", "Supplier transfer test ${UUID.randomUUID().toString().take(8)}")
            put("location_details", "internal transfer test only")
            put("is_default", 0)
            put("is_active", 1)
        })
        val beforeInvoices = db.rawQuery(
            "SELECT COUNT(*) FROM supplier_invoices WHERE station_id=1 AND source_type='product_purchase' AND is_deleted=0",
            null
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        val beforeJournals = db.rawQuery(
            "SELECT COUNT(*) FROM journal_entries WHERE station_id=1 AND reference_type='product_purchase' AND is_deleted=0",
            null
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        helper.transferStockMovement(
            JSONObject()
                .put("product_id", productId)
                .put("source_warehouse_id", warehouseId)
                .put("target_warehouse_id", secondWarehouseId)
                .put("quantity", 2.0)
                .put("unit_cost", 5.0)
                .put("reference_code", "INTERNAL-TRANSFER")
                .put("notes", "اختبار استثناء التحويل الداخلي"),
            1, actorId
        )
        assertEquals(beforeInvoices, db.rawQuery(
            "SELECT COUNT(*) FROM supplier_invoices WHERE station_id=1 AND source_type='product_purchase' AND is_deleted=0",
            null
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        assertEquals(beforeJournals, db.rawQuery(
            "SELECT COUNT(*) FROM journal_entries WHERE station_id=1 AND reference_type='product_purchase' AND is_deleted=0",
            null
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        assertEquals(15.0, db.rawQuery(
            "SELECT COALESCE(SUM(quantity_on_hand),0) FROM inventory_levels WHERE product_id=?",
            arrayOf(productId.toString())
        ).use { c -> c.moveToFirst(); c.getDouble(0) }, 0.0001)
        assertEquals(2, db.rawQuery(
            "SELECT COUNT(*) FROM supplier_invoices WHERE station_id=1 AND source_type='product_purchase' AND source_id IN (?,?) AND is_deleted=0",
            arrayOf(creditMovementId.toString(), cashMovementId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) })
    }


    @Test
    fun fuelSupplyIdempotencyIndependentlyPreventsDuplicateSourceInvoiceAndJournal() {
        val actorId = createFinanceActor()
        createCashBoxAndPostingAccounts(actorId)
        val (tankId, fuelTypeId) = createFuelTank(actorId)
        val key = "fuel-independent-\${UUID.randomUUID()}"
        val payload = JSONObject()
            .put("supplier_id", supplierId)
            .put("tank_id", tankId)
            .put("fuel_type_id", fuelTypeId)
            .put("ordered_quantity", 8.0)
            .put("unit_price", 4.0)
            .put("payment_method", "credit")
            .put("cash_box_id", 0)
            .put("invoice_number", "FUEL-IDEMPOTENT-INDEPENDENT")
            .put("invoice_date", "2026-11-04")
            .put("order_date", "2026-11-04")
            .put("idempotency_key", key)

        val first = helper.saveTankRefillWithPayment(payload, 1, actorId)
        val retry = helper.saveTankRefillWithPayment(payload, 1, actorId)
        assertEquals(first.getLong("refill_id"), retry.getLong("refill_id"))
        assertTrue(retry.optBoolean("idempotent"))
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM tank_refills WHERE station_id=1 AND is_deleted=0 AND extra_data LIKE ?",
            arrayOf("%$key%")
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM supplier_invoices WHERE station_id=1 AND source_type='fuel_refill' AND source_id=? AND is_deleted=0",
            arrayOf(first.getLong("refill_id").toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM journal_entries WHERE station_id=1 AND reference_type='fuel_supply' AND reference_id=? AND is_deleted=0",
            arrayOf(first.getLong("refill_id").toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        assertEquals(8.0, db.rawQuery(
            "SELECT current_quantity FROM tanks WHERE id=? AND station_id=1",
            arrayOf(tankId.toString())
        ).use { c -> check(c.moveToFirst()); c.getDouble(0) }, 0.0001)

        assertTrue(runCatching {
            helper.saveTankRefillWithPayment(JSONObject(payload.toString()).put("unit_price", 5.0), 1, actorId)
        }.isFailure)
    }

    @Test
    fun productPurchaseIdempotencyIndependentlyPreventsDuplicateMovementInvoiceAndJournal() {
        val actorId = createFinanceActor()
        createCashBoxAndPostingAccounts(actorId)
        val categoryId = db.rawQuery(
            "SELECT id FROM product_categories WHERE is_deleted=0 ORDER BY id LIMIT 1", null
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }
        val unitId = db.rawQuery("SELECT id FROM units ORDER BY id LIMIT 1", null)
            .use { c -> check(c.moveToFirst()); c.getLong(0) }
        val productId = helper.insertProduct(
            JSONObject()
                .put("product_name", "Independent idempotency product")
                .put("product_name_ar", "منتج اختبار منع تكرار مستقل")
                .put("category_id", categoryId)
                .put("unit_id", unitId)
                .put("purchase_price", 3.0)
                .put("sale_price", 5.0)
                .put("quantity", 0.0),
            1, actorId
        )
        val warehouseId = helper.ensureOperationalWarehouse(1)
        val key = "product-independent-\${UUID.randomUUID()}"
        val payload = JSONObject()
            .put("movement_type", "in")
            .put("movement_subtype", "in")
            .put("financial_purchase", true)
            .put("payment_method", "credit")
            .put("supplier_id", supplierId)
            .put("product_id", productId)
            .put("warehouse_id", warehouseId)
            .put("quantity", 6.0)
            .put("unit_cost", 3.0)
            .put("movement_code", "SP-PROD-IDEMP-\${UUID.randomUUID().toString().take(8)}")
            .put("invoice_number", "PRODUCT-IDEMPOTENT-INDEPENDENT")
            .put("invoice_date", "2026-11-04")
            .put("idempotency_key", key)

        val firstId = helper.addStockMovement(payload, 1, actorId)
        val retryId = helper.addStockMovement(payload, 1, actorId)
        assertEquals(firstId, retryId)
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM inventory_movements WHERE id=? AND station_id=1 AND is_deleted=0",
            arrayOf(firstId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM supplier_invoices WHERE station_id=1 AND source_type='product_purchase' AND source_id=? AND is_deleted=0",
            arrayOf(firstId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM journal_entries WHERE station_id=1 AND reference_type='product_purchase' AND reference_id=? AND is_deleted=0",
            arrayOf(firstId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        assertEquals(6.0, db.rawQuery(
            "SELECT quantity_on_hand FROM inventory_levels WHERE product_id=? AND warehouse_id=?",
            arrayOf(productId.toString(), warehouseId.toString())
        ).use { c -> check(c.moveToFirst()); c.getDouble(0) }, 0.0001)

        assertTrue(runCatching {
            helper.addStockMovement(JSONObject(payload.toString()).put("unit_cost", 4.0), 1, actorId)
        }.isFailure)
    }

}
