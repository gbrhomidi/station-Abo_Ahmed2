package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import com.aistudio.dieselstationsms.kxmpzq.sms.DebtDueDateRepository
import com.aistudio.dieselstationsms.kxmpzq.sms.SmsOutboxRepository
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
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DebtDueDatePolicyTest {
    private lateinit var context:Context
    private lateinit var helper:DatabaseHelper

    @Before fun setUp() {
        context=ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper=DatabaseHelper.getInstance(context)
    }
    @After fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test fun customerDebtSettlementRepairsMissingMappingsAndPostsCashAndNonCashMethods() {
        DebtDueDateRepository.ensureSchema(helper)
        val db = helper.writableDatabase
        val roleId = db.rawQuery("SELECT id FROM roles ORDER BY id LIMIT 1", null).use {
            assertTrue(it.moveToFirst()); it.getLong(0)
        }
        val actorId = db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", "debt-settlement-user-${System.nanoTime()}")
            put("username", "debt-settlement-${System.nanoTime()}")
            put("password_hash", "test-only")
            put("full_name", "مستخدم اختبار تسديد الديون")
            put("role_id", roleId)
            put("station_id", 1)
        })
        val cashierExists = db.rawQuery("SELECT 1 FROM users WHERE id=1 LIMIT 1", null).use { it.moveToFirst() }
        if (!cashierExists) {
            db.insertOrThrow("users", null, ContentValues().apply {
                put("id", 1L)
                put("uuid", "debt-settlement-cashier-${System.nanoTime()}")
                put("username", "debt-cashier-${System.nanoTime()}")
                put("password_hash", "test-only")
                put("full_name", "Cashier fixture")
                put("role_id", roleId)
                put("station_id", 1)
            })
        }
        val shiftExists = db.rawQuery("SELECT 1 FROM shifts WHERE id=1 LIMIT 1", null).use { it.moveToFirst() }
        if (!shiftExists) {
            db.insertOrThrow("shifts", null, ContentValues().apply {
                put("id", 1L)
                put("uuid", "debt-settlement-shift-${System.nanoTime()}")
                put("shift_code", "DBT-SHIFT-${System.nanoTime()}")
                put("station_id", 1)
                put("shift_date", BusinessDay.currentDate())
                put("shift_type", "morning")
                put("start_time", BusinessDay.currentDate() + " 08:00:00")
                put("cashier_id", 1L)
                put("created_by", actorId)
                put("status", "open")
            })
        }

        val customerId = addParty(db, "settlement-all-methods", "صافي 0 يوم", 0, "explicit_text")
        addSale(
            db, "settlement-all-methods-invoice", customerId,
            BusinessDay.currentDate(), "sale_creation", "explicit_text"
        )
        db.update(
            "parties",
            ContentValues().apply { put("current_balance", 100.0); put("total_due", 100.0) },
            "id=? AND station_id=1",
            arrayOf(customerId.toString())
        )

        val cashBoxId = db.rawQuery(
            "SELECT id FROM cash_boxes WHERE station_id=1 AND status='active' AND is_deleted=0 ORDER BY id DESC LIMIT 1",
            null
        ).use { assertTrue("A seeded active cash box is required", it.moveToFirst()); it.getLong(0) }
        val cashBefore = db.rawQuery(
            "SELECT current_balance FROM cash_boxes WHERE id=?",
            arrayOf(cashBoxId.toString())
        ).use { assertTrue(it.moveToFirst()); it.getDouble(0) }

        val bankId = db.insertOrThrow("banks", null, ContentValues().apply {
            put("uuid", "debt-settlement-bank-${System.nanoTime()}")
            put("bank_code", "DBT-BANK-${System.nanoTime()}")
            put("bank_name", "Debt Settlement Test Bank")
            put("bank_name_ar", "بنك اختبار تسديد الديون")
            put("is_active", 1)
            put("is_deleted", 0)
        })
        val bankAccountId = db.insertOrThrow("bank_accounts", null, ContentValues().apply {
            put("uuid", "debt-settlement-account-${System.nanoTime()}")
            put("account_code", "DBT-BA-${System.nanoTime()}")
            put("bank_id", bankId)
            put("station_id", 1)
            put("account_name", "Debt Settlement Account")
            put("account_name_ar", "حساب تسوية اختبار")
            put("account_number", "TEST-${System.nanoTime()}")
            put("account_type", "current")
            put("opening_balance", 0.0)
            put("current_balance", 0.0)
            put("available_balance", 0.0)
            put("status", "active")
            put("is_deleted", 0)
        })

        val methods = listOf("cash", "bank_transfer", "cheque", "mobile_money")
        val paymentIds = methods.map { method ->
            helper.processPayment(
                customerId.toInt(), 25.0, method, "Robolectric", "اختبار تسديد دين العميل",
                1, "debt-settlement-$method", actorId,
                if (method == "cash") cashBoxId else 0L,
                if (method == "cash") 0L else bankAccountId
            )
        }
        assertTrue(paymentIds.all { it > 0L })
        assertEquals(4, paymentIds.toSet().size)
        assertEquals(4, db.rawQuery(
            "SELECT COUNT(*) FROM payments WHERE customer_party_id=? AND is_deleted=0",
            arrayOf(customerId.toString())
        ).use { assertTrue(it.moveToFirst()); it.getInt(0) })
        assertEquals(0.0, db.rawQuery(
            "SELECT total_due FROM parties WHERE id=? AND station_id=1",
            arrayOf(customerId.toString())
        ).use { assertTrue(it.moveToFirst()); it.getDouble(0) }, 0.000001)
        db.rawQuery(
            "SELECT paid_amount,remaining_amount,payment_status FROM sales_transactions WHERE customer_party_id=? AND station_id=1",
            arrayOf(customerId.toString())
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(100.0, it.getDouble(0), 0.000001)
            assertEquals(0.0, it.getDouble(1), 0.000001)
            assertEquals("paid", it.getString(2))
        }

        val cashAfter = db.rawQuery(
            "SELECT current_balance FROM cash_boxes WHERE id=?",
            arrayOf(cashBoxId.toString())
        ).use { assertTrue(it.moveToFirst()); it.getDouble(0) }
        assertEquals(cashBefore + 25.0, cashAfter, 0.000001)
        val bankAfter = db.rawQuery(
            "SELECT current_balance,available_balance FROM bank_accounts WHERE id=?",
            arrayOf(bankAccountId.toString())
        ).use { assertTrue(it.moveToFirst()); it.getDouble(0) to it.getDouble(1) }
        assertEquals(75.0, bankAfter.first, 0.000001)
        assertEquals(75.0, bankAfter.second, 0.000001)

        val cashMappings = db.rawQuery(
            "SELECT COUNT(*) FROM accounts WHERE cash_box_id=? AND account_type='asset' AND is_cash_account=1 AND is_active=1 AND is_deleted=0",
            arrayOf(cashBoxId.toString())
        ).use { assertTrue(it.moveToFirst()); it.getInt(0) }
        val bankMappings = db.rawQuery(
            "SELECT COUNT(*) FROM accounts WHERE bank_account_id=? AND account_type='asset' AND is_bank_account=1 AND is_active=1 AND is_deleted=0",
            arrayOf(bankAccountId.toString())
        ).use { assertTrue(it.moveToFirst()); it.getInt(0) }
        assertEquals("Cash box must have exactly one active GL mapping", 1, cashMappings)
        assertEquals("Bank account must have exactly one active GL mapping", 1, bankMappings)
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM cash_movements WHERE reference_type='payment' AND reference_id=? AND is_deleted=0",
            arrayOf(paymentIds.first().toString())
        ).use { assertTrue(it.moveToFirst()); it.getInt(0) })
        assertEquals(3, db.rawQuery(
            "SELECT COUNT(*) FROM bank_ledger WHERE bank_account_id=? AND transaction_type='payment'",
            arrayOf(bankAccountId.toString())
        ).use { assertTrue(it.moveToFirst()); it.getInt(0) })
        assertEquals(4, db.rawQuery(
            "SELECT COUNT(*) FROM journal_entries WHERE reference_type='payment' AND status='posted' AND is_deleted=0",
            null
        ).use { assertTrue(it.moveToFirst()); it.getInt(0) })
        assertTrue(db.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() })
    }

    @Test fun parsesOnlyExplicitEnglishArabicAndZeroTerms() {
        assertEquals(30,DebtDueDateRepository.parseTermsDays("50% مقدم، صافي 30 يوم"))
        assertEquals(30,DebtDueDateRepository.parseTermsDays("NET 30"))
        assertEquals(30,DebtDueDateRepository.parseTermsDays("صافي ٣٠ يوم"))
        assertEquals(15,DebtDueDateRepository.parseTermsDays("خلال 15 يوماً"))
        assertEquals(0,DebtDueDateRepository.parseTermsDays("صافي 0 يوم"))
        assertEquals(0,DebtDueDateRepository.parseTermsDays("نقدي"))
        assertNull(DebtDueDateRepository.parseTermsDays(""))
        assertNull(DebtDueDateRepository.parseTermsDays("50% مقدم"))
        assertNull(DebtDueDateRepository.parseTermsDays("شروط غير واضحة"))
        assertNull(DebtDueDateRepository.parseTermsDays("NET 999 days"))
    }

    @Test fun parserPreservesKnownVsUnknownReasonsWithoutFallback() {
        val known = DebtDueDateRepository.parseTermsResult("صافي ٣٠ يوم")
        assertTrue(known is DebtDueDateRepository.TermsParseResult.Known)
        val knownValue = known as DebtDueDateRepository.TermsParseResult.Known
        assertEquals(30, knownValue.days)
        assertEquals("explicit_text", knownValue.source)
        val unknown = DebtDueDateRepository.parseTermsResult("شروط غير واضحة")
        assertTrue(unknown is DebtDueDateRepository.TermsParseResult.Unknown)
        assertEquals("unsupported_terms_format", (unknown as DebtDueDateRepository.TermsParseResult.Unknown).reason)
        assertNull(DebtDueDateRepository.parseTermsDays(null))
    }

    @Test fun maturityStatusSeparatesDueTodayFromLegacyPaymentStatus() {
        val today = LocalDate.parse("2026-11-05")
        assertEquals("DUE_TODAY", DebtDueDateRepository.maturityStatusFor(100.0, "2026-11-05", today))
        assertEquals("OVERDUE", DebtDueDateRepository.maturityStatusFor(100.0, "2026-11-04", today))
        assertEquals("NOT_DUE", DebtDueDateRepository.maturityStatusFor(100.0, "2026-11-06", today))
        assertEquals("UNDATED", DebtDueDateRepository.maturityStatusFor(100.0, null, today))
        assertEquals("PAID", DebtDueDateRepository.maturityStatusFor(0.0, "2026-11-04", today))
    }

    @Test fun customerDebtSummaryIsCanonicalStationScopedAndUsesOneExplicitBusinessDate() {
        DebtDueDateRepository.ensureSchema(helper)
        val db = helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=OFF")

        val customerA = addParty(db, "central-debt-a", "NET 30", 30, "explicit_text")
        val customerB = addParty(db, "central-debt-b", "NET 15", 15, "explicit_text")

        val overdueId = addSale(db, "central-overdue", customerA, "2026-11-07", "sale_creation", "explicit_text")
        val dueTodayId = addSale(db, "central-due-today", customerA, "2026-11-08", "sale_creation", "explicit_text")
        val futureId = addSale(db, "central-future", customerB, "2026-11-10", "sale_creation", "explicit_text")
        val undatedId = addSale(db, "central-undated", customerB, null, null, "explicit_text")
        val paidId = addSale(db, "central-paid", customerB, "2026-11-08", "sale_creation", "explicit_text")
        val foreignStationId = addSale(db, "central-foreign-station", customerB, "2026-11-07", "sale_creation", "explicit_text")
        val deletedId = addSale(db, "central-deleted", customerB, "2026-11-07", "sale_creation", "explicit_text")
        val cancelledId = addSale(db, "central-cancelled", customerB, "2026-11-07", "sale_creation", "explicit_text")

        db.update("sales_transactions", ContentValues().apply {
            put("paid_amount", 60.0)
            put("remaining_amount", 40.0)
        }, "id=?", arrayOf(dueTodayId.toString()))
        db.update("sales_transactions", ContentValues().apply {
            put("paid_amount", 80.0)
            put("remaining_amount", 20.0)
            put("business_day", "2026-11-20")
        }, "id=?", arrayOf(undatedId.toString()))
        // The insert trigger derives a due date from the customer's default terms. Clear
        // that derived snapshot so this row intentionally exercises the UNDATED contract.
        db.update("sales_transactions", ContentValues().apply {
            putNull("due_date")
            putNull("due_date_basis")
            putNull("due_date_source")
            putNull("payment_terms_at_sale")
            putNull("payment_terms_days_at_sale")
            putNull("payment_terms_source_at_sale")
        }, "id=?", arrayOf(undatedId.toString()))
        db.update("sales_transactions", ContentValues().apply {
            put("paid_amount", 100.0)
            put("remaining_amount", 0.0)
        }, "id=?", arrayOf(paidId.toString()))
        db.update("sales_transactions", ContentValues().apply {
            put("station_id", 2)
        }, "id=?", arrayOf(foreignStationId.toString()))
        db.update("sales_transactions", ContentValues().apply {
            put("is_deleted", 1)
        }, "id=?", arrayOf(deletedId.toString()))
        db.update("sales_transactions", ContentValues().apply {
            put("status", "cancelled")
        }, "id=?", arrayOf(cancelledId.toString()))

        assertEquals(140.0, DebtDueDateRepository.getCustomerOutstandingBalance(helper, 1, customerA), 0.000001)
        assertEquals(120.0, DebtDueDateRepository.getCustomerOutstandingBalance(helper, 1, customerB), 0.000001)
        val summary = DebtDueDateRepository.getCustomerDebtSummary(helper, 1, "2026-11-08")
        assertEquals("2026-11-08", summary.getString("as_of_date"))
        assertEquals("DebtDueDateRepository.readDebts", summary.getString("source"))
        assertEquals(260.0, summary.getDouble("total_customer_debts"), 0.000001)
        assertEquals(260.0, summary.getDouble("customer_debts"), 0.000001)
        assertEquals(100.0, summary.getDouble("total_overdue_amount"), 0.000001)
        assertEquals(4, summary.getInt("open_invoice_count"))
        assertEquals(1, summary.getInt("overdue_invoice_count"))
        assertEquals(2, summary.getInt("due_next_7_days_invoice_count"))
        assertEquals(1, summary.getInt("due_today_invoice_count"))
        assertEquals(1, summary.getInt("undated_invoice_count"))
        assertEquals(2, summary.getInt("debtor_count"))
        assertEquals(1, summary.getInt("paid_invoice_count"))

        val customers = summary.getJSONArray("customer_balances")
        val customerARow = (0 until customers.length()).map { customers.getJSONObject(it) }
            .single { it.getLong("customer_party_id") == customerA }
        val customerBRow = (0 until customers.length()).map { customers.getJSONObject(it) }
            .single { it.getLong("customer_party_id") == customerB }
        assertEquals(140.0, customerARow.getDouble("total_due"), 0.000001)
        assertEquals(100.0, customerARow.getDouble("overdue_amount"), 0.000001)
        assertEquals(2, customerARow.getInt("open_invoice_count"))
        assertEquals(120.0, customerBRow.getDouble("current_balance"), 0.000001)
        assertEquals(20.0, customerBRow.getDouble("undated_amount"), 0.000001)
        assertEquals(1, customerBRow.getInt("undated_invoice_count"))

        val bundle = DebtDueDateRepository.getCustomerDebtReportBundle(
            helper, 1, "2026-11-07", "2026-11-10", "2026-11-08"
        )
        assertEquals("2026-11-08", bundle.getString("as_of_date"))
        val bundleSummary = bundle.getJSONObject("summary")
        assertEquals(summary.getDouble("total_customer_debts"), bundleSummary.getDouble("total_customer_debts"), 0.000001)
        assertEquals(summary.getDouble("total_overdue_amount"), bundleSummary.getDouble("total_overdue_amount"), 0.000001)
        val bundleInvoices = bundle.getJSONArray("invoices")
        assertEquals(3, bundleInvoices.length())
        assertEquals(
            setOf(overdueId, dueTodayId, futureId),
            (0 until bundleInvoices.length()).map { bundleInvoices.getJSONObject(it).getLong("transaction_id") }.toSet()
        )
        val invoiceRows = DebtDueDateRepository.getCustomerDebts(helper, 1, asOfDate = "2026-11-08")
        assertFalse(
            "Fully settled credit invoices must not appear in the customer-debt management worklist",
            (0 until invoiceRows.length()).any { invoiceRows.getJSONObject(it).getLong("transaction_id") == paidId }
        )
        assertEquals("Paid invoices remain counted in the summary history", 1, summary.getInt("paid_invoice_count"))
        val overdueJson = (0 until invoiceRows.length()).map { invoiceRows.getJSONObject(it) }
            .single { it.getLong("transaction_id") == overdueId }
        val dueTodayJson = (0 until invoiceRows.length()).map { invoiceRows.getJSONObject(it) }
            .single { it.getLong("transaction_id") == dueTodayId }
        assertEquals("OVERDUE", overdueJson.getString("maturity_status"))
        assertEquals(-1, overdueJson.getInt("days_remaining"))
        assertEquals("DUE_TODAY", dueTodayJson.getString("maturity_status"))
        assertEquals(0, dueTodayJson.getInt("days_remaining"))

        // Changing the explicit operating date changes only maturity buckets, not the
        // persisted invoice balance or the device clock used by the test runner.
        val nextDay = DebtDueDateRepository.getCustomerDebtSummary(helper, 1, "2026-11-09")
        assertEquals(260.0, nextDay.getDouble("total_customer_debts"), 0.000001)
        assertEquals(140.0, nextDay.getDouble("total_overdue_amount"), 0.000001)
        assertEquals(2, nextDay.getInt("overdue_invoice_count"))
        assertEquals(1, nextDay.getInt("due_next_7_days_invoice_count"))
        assertEquals(0, nextDay.getInt("due_today_invoice_count"))

        var invalidAsOfRejected = false
        try {
            DebtDueDateRepository.getCustomerDebtSummary(helper, 1, "2026-02-30")
        } catch (_: IllegalArgumentException) {
            invalidAsOfRejected = true
        }
        assertTrue("A non-calendar as-of date must never be accepted", invalidAsOfRejected)

        // Keep references live so a mistaken test fixture edit cannot silently remove a case.
        assertTrue(overdueId > 0L && futureId > 0L && undatedId > 0L)
    }

    @Test fun creditRiskDoesNotOverrideMaturityState() {
        val today=LocalDate.parse("2026-11-05")
        assertEquals("current",DebtDueDateRepository.statusFor(100.0,0.0,"2026-11-15","critical",today))
        assertEquals("overdue",DebtDueDateRepository.statusFor(100.0,0.0,"2026-11-04","normal",today))
        assertEquals("undated",DebtDueDateRepository.statusFor(100.0,0.0,"","normal",today))
        assertEquals("paid",DebtDueDateRepository.statusFor(0.0,100.0,"2026-11-04","critical",today))
    }

    @Test fun sqliteCreatesNormalizedTermsAndVersionFourTriggers() {
        DebtDueDateRepository.ensureSchema(helper)
        val db=helper.readableDatabase
        assertTrue(db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='payment_terms'",null).use { it.moveToFirst() })
        assertEquals(18,db.rawQuery("SELECT COUNT(*) FROM payment_terms WHERE is_active=1",null).use { it.moveToFirst();it.getInt(0) })
        assertEquals(9, db.rawQuery("SELECT COUNT(*) FROM payment_terms WHERE is_active=1 AND applies_to='supplier'", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(listOf(1,3,7,15,30,45,60,90), db.rawQuery(
            "SELECT days FROM payment_terms WHERE is_active=1 AND days>0 AND applies_to IN ('both','customer') ORDER BY sort_order,id", null
        ).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } })
        assertEquals("بعد يوم واحد", db.rawQuery("SELECT name_ar FROM payment_terms WHERE code='AFTER_1'", null).use { it.moveToFirst(); it.getString(0) })
        assertEquals("بعد 3 أيام", db.rawQuery("SELECT name_ar FROM payment_terms WHERE code='AFTER_3'", null).use { it.moveToFirst(); it.getString(0) })
        assertEquals(30,db.rawQuery("SELECT days FROM payment_terms WHERE code='NET_30'",null).use { it.moveToFirst();it.getInt(0) })
        assertTrue(db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name='trg_debt_due_date_sales_insert_v4'",null).use { it.moveToFirst() })
        assertTrue(db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name='trg_debt_due_date_sales_update_v4'",null).use { it.moveToFirst() })
        assertTrue(db.rawQuery("PRAGMA table_info(parties)",null).use { c -> var found=false;while(c.moveToNext()) if(c.getString(c.getColumnIndexOrThrow("name"))=="payment_terms_source") found=true;found })
        assertTrue(db.rawQuery("PRAGMA table_info(sales_transactions)",null).use { c -> var found=false;while(c.moveToNext()) if(c.getString(c.getColumnIndexOrThrow("name"))=="payment_terms_source_at_sale") found=true;found })
    }

    @Test fun customerTermListExcludesCashAndSupplierTermsAfterLegacyScopeMigration() {
        DebtDueDateRepository.ensureSchema(helper)
        val legacyDb = helper.writableDatabase
        // Simulate a real V59 catalogue: built-ins were incorrectly shared by customers and suppliers.
        legacyDb.execSQL("UPDATE payment_terms SET applies_to='both' WHERE station_id IS NULL AND code IN ('AFTER_1','AFTER_3','NET_7','NET_15','NET_30','NET_45','NET_60','NET_90')")
        legacyDb.insertOrThrow("payment_terms", null, ContentValues().apply {
            put("code", "SUPPLIER_ONLY_TEST")
            put("name_ar", "شرط مورد اختباري")
            put("name_en", "Supplier-only test term")
            put("days", 14)
            put("applies_to", "supplier")
            put("station_id", 1)
            put("is_default", 0)
            put("is_active", 1)
            put("sort_order", 900)
        })
        val supplierTypeId = legacyDb.rawQuery(
            "SELECT id FROM party_types WHERE type_code='SUPPLIER' AND is_deleted=0 AND is_active=1 LIMIT 1",
            null
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getLong(0) }
        val supplierId = legacyDb.insertOrThrow("parties", null, ContentValues().apply {
            put("uuid", "supplier-term-scope-${System.nanoTime()}")
            put("party_code", "SUPPLIER-TERM-${System.nanoTime()}")
            put("party_type_id", supplierTypeId)
            put("station_id", 1)
            put("legal_name", "Supplier term scope acceptance")
            put("commercial_name_ar", "مورد اختبار نطاق شروط الدفع")
            put("is_active", 1)
            put("is_deleted", 0)
        })

        // Force SQLiteOpenHelper through the actual V59 -> V60 migration path.
        legacyDb.version = 59
        DatabaseHelper.closeInstance()
        helper = DatabaseHelper.getInstance(context)
        val migratedDb = helper.writableDatabase
        assertEquals("The V59 -> V61 upgrade must execute for existing installations", 61, migratedDb.version)

        val rows = DebtDueDateRepository.getPaymentTerms(helper, 1, "customer")
        assertEquals(8, rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            assertTrue(row.getInt("days") > 0)
            assertEquals("customer", row.getString("applies_to"))
            assertTrue(row.getString("name_ar").startsWith("بعد "))
            assertFalse(row.getString("name_ar").contains("صافي"))
        }
        val suppliers = DebtDueDateRepository.getPaymentTerms(helper, 1, "supplier")
        // The supplier catalogue intentionally includes eight global positive-day terms plus
        // this station-specific 14-day term. The zero-day cash term is excluded by getPaymentTerms.
        assertEquals(9, suppliers.length())
        val supplierRows = (0 until suppliers.length()).map { suppliers.getJSONObject(it) }
        assertTrue(supplierRows.all { it.getString("applies_to") == "supplier" && it.getInt("days") > 0 })
        assertEquals(1, supplierRows.count { it.getString("code") == "SUPPLIER_ONLY_TEST" })
        assertEquals(1, supplierRows.count { it.optInt("station_id", 0) == 1 })
        assertTrue(supplierRows.none { it.getString("code") in setOf("NET_7", "NET_30", "AFTER_1", "AFTER_3") })

        assertEquals("صافي 14 يوم", DebtDueDateRepository.persistPartyTerms(
            helper, supplierId, 1, "صافي 14 يوم"
        ))
        val persistedSupplierTermId = migratedDb.rawQuery(
            "SELECT payment_terms_id FROM parties WHERE id=? AND station_id=1",
            arrayOf(supplierId.toString())
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getLong(0) }
        assertEquals("Supplier profiles must resolve supplier-scoped terms, never a customer term",
            supplierRows.single { it.getString("code") == "SUPPLIER_ONLY_TEST" }.getLong("id"),
            persistedSupplierTermId)
    }

    @Test fun preDueReminderCandidateAndOutboxValidationUseConfiguredCalendarWindow() {
        DebtDueDateRepository.ensureSchema(helper)
        val db = helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=OFF")
        val customerId = addParty(db, "pre-due-reminder", "صافي 30 يوم", 30, "explicit_text")
        val phone = "967771234567"
        db.insertOrThrow("party_contacts", null, ContentValues().apply {
            put("uuid", "pre-due-contact-${System.nanoTime()}")
            put("party_id", customerId)
            put("contact_name", "Pre-due integration customer")
            put("phone", phone)
            put("is_primary", 1)
            put("is_active", 1)
            put("is_deleted", 0)
        })
        val today = LocalDate.parse(BusinessDay.currentDate())
        val dueDate = today.plusDays(2).toString()
        val saleId = addSale(db, "pre-due-invoice", customerId, dueDate, "sale_creation", "explicit_text")

        helper.setSetting("debt_reminder_days_before_due", "3")
        assertEquals(3, DebtDueDateRepository.reminderLeadDays(helper))
        val candidates = DebtDueDateRepository.getDueSoonDebtReminders(helper, 1, 3)
        val candidate = (0 until candidates.length()).map { candidates.getJSONObject(it) }
            .single { it.getLong("customer_party_id") == customerId }
        assertEquals(2, candidate.getInt("days_remaining"))
        assertTrue(candidate.getBoolean("eligible"))
        val body = "مرحباً، يتبقى 2 يوم على موعد استحقاق الدين ($dueDate). " +
            "المبلغ المستحق بتاريخ $dueDate 100 ر. ي، وإجمالي الرصيد القائم 100 ر. ي."
        val eventId = candidate.getString("event_id")
        assertTrue("A pre-due reminder must have its own outbox stage", eventId.endsWith(":pre"))
        val entity = "party:$customerId"
        assertTrue(
            "A pre-due SMS that still matches the trusted invoice, remaining days, phone and balances must pass the final SQLite gate",
            DebtDueDateRepository.isCurrentDebtReminder(helper, eventId, entity, phone, body)
        )

        // An already-queued pre-due notice must not suppress the independent due-day event.
        db.update(
            "sales_transactions",
            ContentValues().apply { put("due_date", today.toString()) },
            "id=? AND station_id=?",
            arrayOf(saleId.toString(), "1")
        )
        val dueTodayRows = DebtDueDateRepository.getDueSoonDebtReminders(helper, 1, 3)
        val dueTodayCandidate = (0 until dueTodayRows.length()).map { dueTodayRows.getJSONObject(it) }
            .single { it.getLong("customer_party_id") == customerId }
        val dueTodayEventId = dueTodayCandidate.getString("event_id")
        assertTrue("The due-day notice needs a distinct stage-specific dedupe key", dueTodayEventId.endsWith(":due"))
        assertFalse("Pre-due and due-day notices must not share an outbox identity", eventId == dueTodayEventId)
        assertFalse("A pre-due message becomes stale once the invoice reaches its due date",
            DebtDueDateRepository.isCurrentDebtReminder(helper, eventId, entity, phone, body))
        // Simulate the due-day message that was sent yesterday, before the operating
        // date rolled over to today. Its ID must use that same invoice maturity date.
        val yesterdayDueDate = today.minusDays(1).toString()
        val yesterdayDueEventId = "debt-reminder:1:$customerId:$yesterdayDueDate:$saleId:due"
        val dueDayDedupeKey = "$yesterdayDueEventId:$phone"
        assertTrue(SmsOutboxRepository.enqueue(
            helper, phone, "Due day already sent", eventId = yesterdayDueEventId,
            businessEntityId = entity, dedupeKey = dueDayDedupeKey
        ) != null)
        db.update(
            "sms_outbox",
            ContentValues().apply { put("status", "DELIVERED") },
            "dedupe_key=?",
            arrayOf(dueDayDedupeKey)
        )
        db.update(
            "sales_transactions",
            ContentValues().apply { put("due_date", yesterdayDueDate) },
            "id=? AND station_id=?",
            arrayOf(saleId.toString(), "1")
        )
        val overdueAfterSent = DebtDueDateRepository.getOverdueReminderCandidates(helper, 1)
            .let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } }
            .single { it.getLong("customer_party_id") == customerId }
        assertFalse("A delivered due-day reminder must prevent a duplicate overdue catch-up",
            overdueAfterSent.getBoolean("eligible"))

        db.update(
            "sms_outbox",
            ContentValues().apply { put("status", "CANCELLED") },
            "dedupe_key=?",
            arrayOf(dueDayDedupeKey)
        )
        val overdueAfterCancelled = DebtDueDateRepository.getOverdueReminderCandidates(helper, 1)
            .let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } }
            .single { it.getLong("customer_party_id") == customerId }
        assertTrue("A cancelled due-day message must not block overdue recovery",
            overdueAfterCancelled.getBoolean("eligible"))

        db.update(
            "sales_transactions",
            ContentValues().apply { put("due_date", dueDate) },
            "id=? AND station_id=?",
            arrayOf(saleId.toString(), "1")
        )

        helper.setSetting("debt_reminder_days_before_due", "1")
        assertEquals(1, DebtDueDateRepository.reminderLeadDays(helper))
        assertFalse(
            "Changing the lead window must invalidate an already-queued reminder outside the newly configured window",
            DebtDueDateRepository.isCurrentDebtReminder(helper, eventId, entity, phone, body)
        )
        helper.setSetting("debt_reminder_days_before_due", "3")
        db.update(
            "sales_transactions",
            ContentValues().apply { put("due_date", today.plusDays(4).toString()) },
            "id=? AND station_id=?",
            arrayOf(saleId.toString(), "1")
        )
        assertFalse(
            "Moving the maturity date beyond the configured lead window must cancel the stale pre-due event",
            DebtDueDateRepository.isCurrentDebtReminder(helper, eventId, entity, phone, body)
        )
    }

    @Test fun schemaInitializationDoesNotBackfillHistoricalSales() {
        DebtDueDateRepository.ensureSchema(helper)
        val db=helper.writableDatabase
        db.execSQL("DROP TRIGGER IF EXISTS trg_debt_due_date_sales_insert_v4")
        db.execSQL("DROP TRIGGER IF EXISTS trg_debt_due_date_sales_update_v4")
        db.execSQL("PRAGMA foreign_keys=OFF")
        val partyId=addParty(db,"historical-no-backfill","صافي 30 يوم",30,"explicit_text")
        val saleId=addSale(db,"historical-no-backfill",partyId,null,null,"explicit_text")

        DebtDueDateRepository.ensureSchema(helper)

        db.rawQuery(
            "SELECT due_date,due_date_source FROM sales_transactions WHERE id=?",
            arrayOf(saleId.toString())
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertTrue("schema setup must not write a historical due date", cursor.isNull(0))
            assertTrue("schema setup must not label a historical reconstruction", cursor.isNull(1))
        }
        assertFalse(
            "historical migration bookkeeping must not be created by the read/schema path",
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='debt_due_date_migrations'",
                null
            ).use { it.moveToFirst() }
        )
    }

    @Test fun historicalReconstructionRequiresAnInvoiceTimeSnapshotAndIsIdempotent() {
        DebtDueDateRepository.ensureSchema(helper)
        val db = helper.writableDatabase
        db.execSQL("DROP TRIGGER IF EXISTS trg_debt_due_date_sales_insert_v4")
        db.execSQL("DROP TRIGGER IF EXISTS trg_debt_due_date_sales_update_v4")
        db.execSQL("PRAGMA foreign_keys=OFF")
        val customerId = addParty(db, "historical-snapshot-proof", "صافي 90 يوم", 90, "explicit_text")
        val saleId = addSale(db, "historical-snapshot-proof-invoice", customerId, null, null, "explicit_text")
        db.update("sales_transactions", ContentValues().apply {
            put("payment_terms_at_sale", "صافي 7 أيام")
            put("payment_terms_days_at_sale", 7)
            put("payment_terms_source_at_sale", "explicit_text")
            put("due_date_basis", "2026-11-01")
        }, "id=?", arrayOf(saleId.toString()))

        DebtDueDateRepository.ensureSchema(helper)
        DebtDueDateRepository.ensureSchema(helper)

        db.rawQuery("SELECT due_date,due_date_source FROM sales_transactions WHERE id=?", arrayOf(saleId.toString())).use { c ->
            check(c.moveToFirst())
            assertEquals("2026-11-08", c.getString(0))
            assertEquals("historical_snapshot_reconstruction", c.getString(1))
        }
    }

    @Test fun unknownLegacyTermsAreNotEligibleButExplicitTermsCreateDueDates() {
        DebtDueDateRepository.ensureSchema(helper)
        val db=helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=OFF")
        val legacyParty=addParty(db,"old-default","صافي 30 يوم",30,null)
        val explicitParty=addParty(db,"explicit","صافي 15 يوم",15,"explicit_text")
        val legacySale=addSale(db,"old-due",legacyParty,"2026-10-01","sale_creation")
        val explicitSale=addSale(db,"new-explicit",explicitParty,null,null)

        val rows=DebtDueDateRepository.getCustomerDebts(helper,1)
        val old=rows.getJSONObject(0)
        val current=rows.getJSONObject(1)
        assertEquals(legacySale,old.getLong("transaction_id"))
        assertEquals("",old.getString("due_date"))
        assertEquals("undated",old.getString("status"))
        assertEquals(explicitSale,current.getLong("transaction_id"))
        assertEquals("2026-11-16",current.getString("due_date"))
        assertEquals("explicit_text",db.rawQuery("SELECT payment_terms_source_at_sale FROM sales_transactions WHERE id=?",arrayOf(explicitSale.toString())).use { it.moveToFirst();it.getString(0) })
        assertEquals("sales_trigger_v4",db.rawQuery("SELECT due_date_source FROM sales_transactions WHERE id=?",arrayOf(explicitSale.toString())).use { it.moveToFirst();it.getString(0) })
        assertEquals(1,helper.getCustomerDebts(null,null,1).length())
        val dashboard=helper.getDashboardStatsForDate(1,JSONObject(),"2026-11-06")
        assertEquals(0,dashboard.getInt("overdue_count"))
        assertEquals(0,dashboard.getInt("due_invoices"))
        val taskRows=helper.getPendingTasks(1,JSONObject().put("limit",5000))
        val oldTask=(0 until taskRows.length()).map { taskRows.getJSONObject(it) }
            .first { it.optString("source_kind")=="credit_sale" && it.optLong("source_id")==legacySale }
        assertEquals("بحاجة مراجعة",oldTask.getString("status"))
        assertEquals("",oldTask.getString("task_date"))
    }


    @Test fun selectedPaymentTermIsSnapshottedAndSurvivesCustomerProfileChanges() {
        DebtDueDateRepository.ensureSchema(helper)
        val db = helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=OFF")
        val customerId = addParty(db, "selected-term-customer", "صافي 30 يوم", 30, "explicit_text")
        val saleId = addSale(db, "selected-term-invoice", customerId, null, null, "explicit_text")
        val netSevenId = db.rawQuery(
            "SELECT id FROM payment_terms WHERE code='NET_7' AND station_id IS NULL LIMIT 1", null
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }

        val today = LocalDate.parse(BusinessDay.currentDate())
        val invoiceDate = today.minusDays(7).toString()
        val dateColumns = db.rawQuery("PRAGMA table_info(sales_transactions)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        assertEquals(1, db.update("sales_transactions", ContentValues().apply {
            listOf("business_day", "transaction_date", "sale_date")
                .filter { it in dateColumns }
                .forEach { put(it, invoiceDate) }
        }, "id=? AND station_id=1", arrayOf(saleId.toString())))
        val applied = DebtDueDateRepository.applySelectedPaymentTermSnapshot(db, saleId, 1, customerId, netSevenId, allowExistingSnapshot = true)
        assertTrue(applied.getBoolean("success"))
        assertEquals(7, applied.getInt("payment_terms_days"))
        assertEquals(today.toString(), applied.getString("due_date"))

        DebtDueDateRepository.persistPartyTerms(helper, customerId, 1, "صافي 0 يوم")
        db.rawQuery(
            """SELECT payment_terms_id_at_sale,payment_terms_days_at_sale,payment_terms_source_at_sale,
                      payment_terms_at_sale,due_date,due_date_basis,due_date_source
               FROM sales_transactions WHERE id=? AND station_id=1""",
            arrayOf(saleId.toString())
        ).use { c ->
            check(c.moveToFirst())
            assertEquals(netSevenId, c.getLong(0))
            assertEquals(7, c.getInt(1))
            assertEquals("explicit_text", c.getString(2))
            assertEquals("بعد 7 أيام", c.getString(3))
            assertEquals(today.toString(), c.getString(4))
            assertEquals(invoiceDate, c.getString(5))
            assertEquals("sale_creation", c.getString(6))
        }
        val rows = DebtDueDateRepository.getCustomerDebts(helper, 1)
        val debt = (0 until rows.length()).map { rows.getJSONObject(it) }
            .first { it.getLong("transaction_id") == saleId }
        assertEquals(today.toString(), debt.getString("due_date"))
        assertEquals("current", debt.getString("status"))
    }

    @Test fun transactionDateTakesPrecedenceOverBusinessDayForNewInvoiceDueDate() {
        DebtDueDateRepository.ensureSchema(helper)
        val db = helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=OFF")
        val customerId = addParty(db, "transaction-date-priority", "صافي 30 يوم", 30, "explicit_text")
        val saleId = addSale(db, "transaction-date-priority-invoice", customerId, null, null, "explicit_text")
        db.update("sales_transactions", ContentValues().apply {
            put("business_day", "2026-11-01")
            put("transaction_date", "2026-11-04")
        }, "id=?", arrayOf(saleId.toString()))
        val netSevenId = db.rawQuery(
            "SELECT id FROM payment_terms WHERE code='NET_7' AND station_id IS NULL LIMIT 1", null
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }

        val snapshot = DebtDueDateRepository.applySelectedPaymentTermSnapshot(db, saleId, 1, customerId, netSevenId, allowExistingSnapshot = true)

        assertEquals("2026-11-04", snapshot.getString("due_date_basis"))
        assertEquals("2026-11-11", snapshot.getString("due_date"))
    }

    @Test fun selectedNetThirtyTermCreatesInvoiceTimeSnapshot() {
        DebtDueDateRepository.ensureSchema(helper)
        val db = helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=OFF")
        val customerId = addParty(db, "net-thirty-customer", "صافي 7 أيام", 7, "explicit_text")
        val saleId = addSale(db, "net-thirty-invoice", customerId, null, null, "explicit_text")
        val netThirtyId = db.rawQuery(
            "SELECT id FROM payment_terms WHERE code='NET_30' AND station_id IS NULL LIMIT 1", null
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }

        val snapshot = DebtDueDateRepository.applySelectedPaymentTermSnapshot(
            db, saleId, 1, customerId, netThirtyId, allowExistingSnapshot = true
        )

        assertEquals(30, snapshot.getInt("payment_terms_days"))
        assertEquals("2026-12-01", snapshot.getString("due_date"))
        db.rawQuery("SELECT due_date_basis,due_date_source FROM sales_transactions WHERE id=?", arrayOf(saleId.toString())).use { c ->
            check(c.moveToFirst())
            assertEquals("2026-11-01", c.getString(0))
            assertEquals("sale_creation", c.getString(1))
        }
    }

    @Test fun selectedTermRejectsInvalidForeignStationAndCashSaleTerms() {
        DebtDueDateRepository.ensureSchema(helper)
        val db = helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=OFF")
        val customerId = addParty(db, "term-scope-customer", "صافي 30 يوم", 30, "explicit_text")
        val saleId = addSale(db, "term-scope-invoice", customerId, null, null, "explicit_text")
        val foreignStationTermId = db.insertOrThrow("payment_terms", null, ContentValues().apply {
            put("code", "OTHER-STATION-${System.nanoTime()}")
            put("name_ar", "شرط محطة أخرى"); put("name_en", "Other station")
            put("days", 10); put("applies_to", "customer"); put("station_id", 2)
            put("is_active", 1); put("sort_order", 99)
        })
        val netSevenId = db.rawQuery(
            "SELECT id FROM payment_terms WHERE code='NET_7' AND station_id IS NULL LIMIT 1", null
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }

        listOf(Long.MAX_VALUE, foreignStationTermId).forEach { termId ->
            var rejected = false
            try {
                DebtDueDateRepository.applySelectedPaymentTermSnapshot(
                    db, saleId, 1, customerId, termId, allowExistingSnapshot = true
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Invalid or cross-station payment terms must be rejected", rejected)
        }

        val cashSaleId = addSale(db, "term-scope-cash-invoice", customerId, null, null)
        db.update("sales_transactions", ContentValues().apply {
            put("payment_method", "cash"); put("is_credit", 0)
        }, "id=?", arrayOf(cashSaleId.toString()))
        var cashRejected = false
        try {
            DebtDueDateRepository.applySelectedPaymentTermSnapshot(
                db, cashSaleId, 1, customerId, netSevenId, allowExistingSnapshot = true
            )
        } catch (_: IllegalArgumentException) {
            cashRejected = true
        }
        assertTrue("Cash invoices must not enter the credit-term flow", cashRejected)
    }

    @Test fun newCreditInvoiceCannotCommitWithoutAValidDateBasis() {
        DebtDueDateRepository.ensureSchema(helper)
        val db = helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=OFF")
        val customerId = addParty(db, "missing-invoice-date", "صافي 30 يوم", 30, "explicit_text")
        val saleId = addSale(db, "missing-invoice-date-invoice", customerId, null, null, "explicit_text")
        db.execSQL("DROP TRIGGER IF EXISTS trg_debt_due_date_sales_insert_v4")
        db.execSQL("DROP TRIGGER IF EXISTS trg_debt_due_date_sales_update_v4")
        val columns = db.rawQuery("PRAGMA table_info(sales_transactions)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        val withoutDates = ContentValues().apply {
            putNull("transaction_date"); putNull("business_day"); putNull("due_date")
            putNull("due_date_basis"); putNull("due_date_source")
            if ("sale_date" in columns) putNull("sale_date")
        }
        db.update("sales_transactions", withoutDates, "id=?", arrayOf(saleId.toString()))
        val netSevenId = db.rawQuery(
            "SELECT id FROM payment_terms WHERE code='NET_7' AND station_id IS NULL LIMIT 1", null
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }

        var rejected = false
        try {
            DebtDueDateRepository.applySelectedPaymentTermSnapshot(
                db, saleId, 1, customerId, netSevenId, allowExistingSnapshot = true
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("A credit invoice without an invoice-time basis must not be accepted", rejected)
        db.rawQuery("SELECT due_date FROM sales_transactions WHERE id=?", arrayOf(saleId.toString())).use { c ->
            check(c.moveToFirst())
            assertTrue("Rejected invoice must remain without a fabricated due date", c.isNull(0))
        }
    }

    @Test fun selectedPaymentTermRejectsDifferentCustomerAndPreservesOriginalSnapshot() {
        DebtDueDateRepository.ensureSchema(helper)
        val db = helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=OFF")
        val customerId = addParty(db, "selected-term-owner", "صافي 30 يوم", 30, "explicit_text")
        val otherCustomerId = addParty(db, "selected-term-other", "صافي 15 يوم", 15, "explicit_text")
        val saleId = addSale(db, "selected-term-owner-invoice", customerId, null, null, "explicit_text")
        val netThirtyId = db.rawQuery(
            "SELECT id FROM payment_terms WHERE code='NET_30' AND station_id IS NULL LIMIT 1", null
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }
        val netSevenId = db.rawQuery(
            "SELECT id FROM payment_terms WHERE code='NET_7' AND station_id IS NULL LIMIT 1", null
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }

        DebtDueDateRepository.applySelectedPaymentTermSnapshot(db, saleId, 1, customerId, netThirtyId, allowExistingSnapshot = true)
        var rejected = false
        try {
            DebtDueDateRepository.applySelectedPaymentTermSnapshot(db, saleId, 1, otherCustomerId, netSevenId)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("A payment term cannot be attached using a different customer identity", rejected)
        var rewriteRejected = false
        try {
            DebtDueDateRepository.applySelectedPaymentTermSnapshot(db, saleId, 1, customerId, netSevenId)
        } catch (_: IllegalArgumentException) {
            rewriteRejected = true
        }
        assertTrue("A saved invoice snapshot cannot be rewritten outside a new-sale transaction", rewriteRejected)
        db.rawQuery(
            "SELECT due_date,payment_terms_days_at_sale,payment_terms_id_at_sale FROM sales_transactions WHERE id=?",
            arrayOf(saleId.toString())
        ).use { c ->
            check(c.moveToFirst())
            assertEquals("2026-12-01", c.getString(0))
            assertEquals(30, c.getInt(1))
            assertEquals(netThirtyId, c.getLong(2))
        }
    }

    @Test fun legacyReminderQueriesMatchKotlinTrustedDateEligibility() {
        DebtDueDateRepository.ensureSchema(helper)
        val db=helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=OFF")
        val today=LocalDate.parse(BusinessDay.currentDate())
        val past=today.minusDays(1).toString()
        val future=today.plusDays(5).toString()
        val unknownParty=addParty(db,"untrusted-reminder","NET 30",30,null)
        val overdueParty=addParty(db,"trusted-overdue","NET 30",30,"explicit_text")
        val soonParty=addParty(db,"trusted-soon","NET 30",30,"explicit_text")
        val unknownSale=addSale(db,"untrusted-overdue",unknownParty,past,"sale_creation")
        val trustedOverdue=addSale(db,"trusted-overdue",overdueParty,past,"sale_creation","explicit_text")
        val trustedSoon=addSale(db,"trusted-soon",soonParty,future,"sale_creation","explicit_text")

        val overdueRows=helper.getOverduePayments(1)
        val overdueIds=(0 until overdueRows.length())
            .map { overdueRows.getJSONObject(it).getLong("id") }.toSet()
        assertEquals(setOf(trustedOverdue),overdueIds)

        val soonRows=helper.getDueSoonDebtReminders(1,10)
        val sqliteSoonIds=(0 until soonRows.length()).map { soonRows.getJSONObject(it).getLong("transaction_id") }.toSet()
        val kotlinSoon=DebtDueDateRepository.getDueSoonDebtReminders(helper,1,10)
        val kotlinSoonIds=(0 until kotlinSoon.length()).map { kotlinSoon.getJSONObject(it).getLong("transaction_id") }.toSet()
        assertEquals(setOf(trustedSoon),sqliteSoonIds)
        assertEquals(sqliteSoonIds,kotlinSoonIds)

        fun pageIds(status:String)=helper.getDebtRemindersPage(1,status).let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it).getLong("debt_id") }.toSet()
        }
        assertEquals(setOf(trustedOverdue),pageIds("overdue"))
        assertEquals(setOf(trustedSoon),pageIds("pending"))
        assertEquals(setOf(unknownSale),pageIds("undated"))
        val undated=helper.getDebtRemindersPage(1,"undated").getJSONObject(0)
        assertTrue(undated.optString("due_date").isBlank())
    }

    @Test fun outboxDebtReminderIsRevalidatedAgainstCurrentBalanceAndPhone() {
        DebtDueDateRepository.ensureSchema(helper)
        val db=helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=OFF")
        val originalPhone="967771234567"
        val partyId=addParty(db,"outbox-revalidation","صافي 0 يوم",0,"explicit_text")
        db.insertOrThrow("party_contacts",null,ContentValues().apply {
            put("uuid","contact-outbox-${System.nanoTime()}");put("party_id",partyId)
            put("contact_name","Outbox test");put("phone",originalPhone)
            put("is_primary",1);put("is_active",1);put("is_deleted",0)
        })
        val today=BusinessDay.currentDate()
        val originalSaleId=addSale(db,"outbox-revalidation",partyId,today,"sale_creation","explicit_text")

        val secondPhone="967771112222"
        val secondPartyId=addParty(db,"outbox-revalidation-second","صافي 0 يوم",0,"explicit_text")
        db.insertOrThrow("party_contacts",null,ContentValues().apply {
            put("uuid","contact-outbox-second-${System.nanoTime()}");put("party_id",secondPartyId)
            put("contact_name","Outbox test second");put("phone",secondPhone)
            put("is_primary",1);put("is_active",1);put("is_deleted",0)
        })
        addSale(db,"outbox-revalidation-second",secondPartyId,today,"sale_creation","explicit_text")

        val reminderRows=DebtDueDateRepository.getDueSoonDebtReminders(helper,1,0)
        val candidates=(0 until reminderRows.length()).map { reminderRows.getJSONObject(it) }
        val candidate=candidates.first { it.getLong("customer_party_id")==partyId }
        val secondCandidate=candidates.first { it.getLong("customer_party_id")==secondPartyId }
        val eventId=candidate.getString("event_id")
        val entityId="party:$partyId"
        val body="مرحباً، موعد استحقاق الدين هو اليوم ($today). المبلغ المستحق اليوم 100 وإجمالي الرصيد القائم 100 ر. ي."
        assertTrue(DebtDueDateRepository.isCurrentDebtReminder(helper,eventId,entityId,originalPhone,body))

        val batch=(1..100).map { index ->
            val source=if (index % 2 == 0) candidate else secondCandidate
            val phone=if (index % 2 == 0) originalPhone else secondPhone
            SmsOutboxRepository.OutboxMessage(
                messageId="batch-validation-$index",
                recipient=phone,
                body=body,
                partsCount=1,
                attemptCount=0,
                subscriptionId=null,
                eventId=source.getString("event_id"),
                businessEntityId="party:${source.getLong("customer_party_id")}"
            )
        }
        assertEquals(
            "All current reminders in a 100-message batch should validate from the station/customer-scoped read",
            batch.map { it.messageId }.toSet(),
            DebtDueDateRepository.validateDebtReminderBatch(helper,batch)
        )

        db.update("sales_transactions",ContentValues().apply {
            put("paid_amount",100.0);put("remaining_amount",0.0);put("payment_status","paid")
        },"id=?",arrayOf(candidate.getLong("transaction_id").toString()))
        assertFalse("A fully paid debt must not reach SMS transport",DebtDueDateRepository.isCurrentDebtReminder(helper,eventId,entityId,originalPhone,body))

        db.update("sales_transactions",ContentValues().apply {
            put("paid_amount",0.0);put("remaining_amount",100.0);put("payment_status","pending")
        },"id=?",arrayOf(candidate.getLong("transaction_id").toString()))
        db.update("sales_transactions",ContentValues().apply {
            put("paid_amount",50.0);put("remaining_amount",50.0);put("payment_status","partial")
        },"id=?",arrayOf(candidate.getLong("transaction_id").toString()))
        assertFalse("A partially paid debt must not send a stale amount",DebtDueDateRepository.isCurrentDebtReminder(helper,eventId,entityId,originalPhone,body))
        db.update("sales_transactions",ContentValues().apply {
            put("paid_amount",0.0);put("remaining_amount",100.0);put("payment_status","pending")
        },"id=?",arrayOf(candidate.getLong("transaction_id").toString()))
        db.update("party_contacts",ContentValues().apply { put("phone","967779999999") },"party_id=?",arrayOf(partyId.toString()))
        assertFalse("A queued message must not be sent to the customer's old phone",DebtDueDateRepository.isCurrentDebtReminder(helper,eventId,entityId,originalPhone,body))

        db.update("party_contacts",ContentValues().apply { put("phone",originalPhone) },"party_id=?",arrayOf(partyId.toString()))
        db.update("sales_transactions",ContentValues().apply { put("status","cancelled") },
            "id=? AND station_id=1",arrayOf(originalSaleId.toString()))
        assertFalse("A reminder for a cancelled invoice must be rejected at the final pre-transport SQLite gate",
            DebtDueDateRepository.isCurrentDebtReminder(helper,eventId,entityId,originalPhone,body))
    }

    private fun addParty(db:android.database.sqlite.SQLiteDatabase,key:String,terms:String,days:Int,source:String?):Long {
        val values=ContentValues().apply {
            put("uuid","party-$key");put("party_code","P-$key");put("party_type_id",1);put("station_id",1)
            put("commercial_name_ar",key);put("payment_terms",terms);put("payment_terms_days",days)
            if(source!=null) put("payment_terms_source",source) else putNull("payment_terms_source")
            put("is_active",1);put("is_deleted",0)
        }
        return db.insertOrThrow("parties",null,values)
    }

    private fun addSale(db:android.database.sqlite.SQLiteDatabase,key:String,partyId:Long,dueDate:String?,dueSource:String?,termsSourceAtSale:String?=null):Long {
        val salesColumns=db.rawQuery("PRAGMA table_info(sales_transactions)",null).use { c ->
            buildSet { while(c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        val values=ContentValues().apply {
            put("uuid","sale-$key");put("sale_code","SALE-$key");put("station_id",1);put("shift_id",1)
            put("customer_party_id",partyId);put("subtotal",100.0);put("gross_amount",100.0);put("net_amount",100.0)
            put("payment_method","credit");put("payment_status","pending");put("is_credit",1);put("paid_amount",0.0);put("remaining_amount",100.0)
            put("status","completed");put("cashier_id",1);put("is_deleted",0)
            listOf("business_day","transaction_date","sale_date").firstOrNull { it in salesColumns }?.let { put(it,"2026-11-01") }
            if(dueDate!=null) put("due_date",dueDate)
            if(dueSource!=null) put("due_date_source",dueSource)
            if(termsSourceAtSale!=null) put("payment_terms_source_at_sale",termsSourceAtSale)
        }
        return db.insertOrThrow("sales_transactions",null,values)
    }
}
