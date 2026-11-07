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
    }
}
