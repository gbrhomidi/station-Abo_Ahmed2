package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApprovalControlRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private var roleId = 0L
    private var makerId = 0L
    private var checkerId = 0L
    private var otherStationUserId = 0L
    private var journalId = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        val db = helper.writableDatabase
        roleId = db.rawQuery("SELECT id FROM roles ORDER BY id LIMIT 1", null).use { c ->
            check(c.moveToFirst()) { "لا يوجد دور افتراضي للاختبار" }
            c.getLong(0)
        }
        ensureStation(11, "TEST-APPROVAL-11")
        ensureStation(12, "TEST-APPROVAL-12")
        makerId = createUser(11, "maker")
        checkerId = createUser(11, "checker")
        otherStationUserId = createUser(12, "other-station")
        db.insertOrThrow("approval_thresholds", null, ContentValues().apply {
            put("station_id", 11)
            put("entity_type", "journal")
            put("operation", "post")
            put("amount_threshold", 5000.0)
            put("required_approvals", 1)
            put("approver_role_id", roleId)
            put("is_active", 1)
        })
        db.insertOrThrow("approval_thresholds", null, ContentValues().apply {
            put("station_id", 11)
            put("entity_type", "journal")
            put("operation", "reverse")
            put("amount_threshold", 5000.0)
            put("required_approvals", 1)
            put("approver_role_id", roleId)
            put("is_active", 1)
        })
        listOf("payment", "expense").forEach { entity ->
            db.insertOrThrow("approval_thresholds", null, ContentValues().apply {
                put("station_id", 11)
                put("entity_type", entity)
                put("operation", "reverse")
                put("amount_threshold", 5000.0)
                put("required_approvals", 1)
                put("approver_role_id", roleId)
                put("is_active", 1)
            })
        }
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    private fun ensureStation(id: Int, code: String) {
        val db = helper.writableDatabase
        db.rawQuery("SELECT id FROM stations WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) return
        }
        db.insertOrThrow("stations", null, ContentValues().apply {
            put("id", id)
            put("uuid", UUID.randomUUID().toString())
            put("station_code", code)
            put("station_name", code)
            put("status", "active")
            put("is_deleted", 0)
        })
    }

    private fun createUser(stationId: Int, prefix: String): Long =
        helper.writableDatabase.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", "$prefix-${UUID.randomUUID().toString().take(8)}")
            put("password_hash", "test-only")
            put("full_name", prefix)
            put("role_id", roleId)
            put("station_id", stationId)
            put("is_deleted", 0)
            put("status", "active")
        })

    private var cashBoxId = 0L
    private var expenseCategoryId = 0L

    private fun ensureFinancialFixtures() {
        val db = helper.writableDatabase
        if (cashBoxId <= 0L) {
            cashBoxId = db.insertOrThrow("cash_boxes", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("box_code", "MC-CASH-11")
                put("box_name", "Maker checker test cashbox")
                put("station_id", 11)
                put("opening_balance", 10000.0)
                put("current_balance", 10000.0)
                put("status", "active")
                put("is_deleted", 0)
                put("created_by", makerId)
            })
        }
        var cashAccountId = db.rawQuery(
            "SELECT id FROM accounts WHERE account_type='asset' AND is_active=1 AND is_deleted=0 AND cash_box_id IS NULL ORDER BY id LIMIT 1",
            null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        if (cashAccountId <= 0L) {
            cashAccountId = db.insertOrThrow("accounts", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("account_code", "MC-CASH-ACC")
                put("account_name", "Maker checker cash account")
                put("account_name_ar", "حساب نقدي للاختبار")
                put("level", 1); put("account_type", "asset"); put("normal_balance", "debit")
                put("is_active", 1); put("is_deleted", 0)
            })
        }
        db.update("accounts", ContentValues().apply {
            put("cash_box_id", cashBoxId); put("is_cash_account", 1); put("is_active", 1); put("is_deleted", 0)
        }, "id=?", arrayOf(cashAccountId.toString()))

        val payableExists = db.rawQuery(
            """SELECT id FROM accounts WHERE account_type='liability' AND is_active=1 AND is_deleted=0
               AND (account_category LIKE '%pay%' OR account_name LIKE '%supplier%' OR account_name_ar LIKE '%مورد%' OR account_name_ar LIKE '%دائن%')
               ORDER BY id LIMIT 1""", null
        ).use { it.moveToFirst() }
        if (!payableExists) db.insertOrThrow("accounts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("account_code", "MC-PAYABLE")
            put("account_name", "Accounts Payable"); put("account_name_ar", "ذمم الموردين")
            put("account_category", "payable"); put("level", 1)
            put("account_type", "liability"); put("normal_balance", "credit")
            put("is_active", 1); put("is_deleted", 0)
        })

        var expenseAccountId = db.rawQuery(
            "SELECT id FROM accounts WHERE account_type='expense' AND is_active=1 AND is_deleted=0 ORDER BY id LIMIT 1",
            null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        if (expenseAccountId <= 0L) {
            expenseAccountId = db.insertOrThrow("accounts", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString()); put("account_code", "MC-EXP-ACC")
                put("account_name", "Maker checker expense account"); put("account_name_ar", "حساب مصروفات للاختبار")
                put("level", 1); put("account_type", "expense"); put("normal_balance", "debit")
                put("is_active", 1); put("is_deleted", 0)
            })
        }
        if (expenseCategoryId <= 0L) {
            expenseCategoryId = db.insertOrThrow("expense_categories", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString()); put("category_code", "MC-EXP-CAT")
                put("category_name", "Maker checker test expense"); put("category_name_ar", "مصروف اختبار الاعتماد")
                put("default_account_id", expenseAccountId); put("is_active", 1); put("is_deleted", 0)
            })
        }
    }

    private fun cashBalance(): Double = helper.writableDatabase.rawQuery(
        "SELECT current_balance FROM cash_boxes WHERE id=? AND station_id=11",
        arrayOf(cashBoxId.toString())
    ).use { c -> check(c.moveToFirst()); c.getDouble(0) }

    private fun countRows(sql: String, args: Array<String>): Int =
        helper.writableDatabase.rawQuery(sql, args).use { c -> check(c.moveToFirst()); c.getInt(0) }

    private fun createRequest(): Long {
        if (journalId <= 0L) {
            val db = helper.writableDatabase
            val accountIds = mutableListOf<Long>()
            db.rawQuery("SELECT id FROM accounts WHERE is_deleted=0 ORDER BY id LIMIT 2", null).use { cursor ->
                while (cursor.moveToNext()) accountIds.add(cursor.getLong(0))
            }
            while (accountIds.size < 2) {
                val index = accountIds.size + 1
                accountIds.add(db.insertOrThrow("accounts", null, ContentValues().apply {
                    put("uuid", UUID.randomUUID().toString())
                    put("account_code", "TEST-MC-${UUID.randomUUID().toString().take(8)}")
                    put("account_name", "Maker checker test account $index")
                    put("account_name_ar", "حساب اختبار الاعتماد $index")
                    put("level", 1)
                    put("account_type", if (index == 1) "asset" else "equity")
                    put("normal_balance", if (index == 1) "debit" else "credit")
                    put("is_deleted", 0)
                    put("is_active", 1)
                }))
            }
            val journal = JSONObject().apply {
                put("entry_date", "2026-10-12")
                put("description", "Maker-checker approval integration test")
                put("entry_type", "general")
                put("status", "draft")
                put("items", JSONArray()
                    .put(JSONObject().put("account_id", accountIds[0]).put("debit", 6000.0).put("credit", 0.0))
                    .put(JSONObject().put("account_id", accountIds[1]).put("debit", 0.0).put("credit", 6000.0)))
            }
            journalId = helper.saveJournalEntry(journal, makerId, 11)
        }
        return helper.createPendingApproval(11, "journal", journalId, "post", 6000.0, makerId)
    }

    @Test
    fun saveJournalEntryCannotBypassMakerCheckerWithPostedStatus() {
        createRequest()
        val entry = helper.getJournalEntryDetails(journalId, 11)
            ?: throw AssertionError("لم يتم العثور على قيد الاختبار")
        entry.put("status", "posted")

        try {
            helper.saveJournalEntry(entry, makerId, 11)
            throw AssertionError("يجب منع ترحيل القيد عبر مسار الحفظ")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("ترحيل القيد"))
        }

        helper.writableDatabase.rawQuery(
            "SELECT status FROM journal_entries WHERE id=? AND station_id=11",
            arrayOf(journalId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("draft", cursor.getString(0))
        }
        assertEquals(1, helper.getPendingApprovals(11, "pending").length())
    }

    @Test
    fun amountThresholdAndPendingRequestAreEnforcedAndIdempotent() {
        assertTrue(helper.requiresApproval(11, "journal", "post", 6000.0))
        assertTrue(!helper.requiresApproval(11, "journal", "post", 1000.0))
        val first = createRequest()
        val second = createRequest()
        assertEquals(first, second)
        assertEquals(1, helper.getPendingApprovals(11, "pending").length())
    }

    @Test(expected = IllegalArgumentException::class)
    fun makerCannotApproveOwnRequest() {
        helper.approveTransaction(createRequest(), makerId, 11, "self approval must fail")
    }

    @Test
    fun separateAuthorizedCheckerApprovesAndPostsTheUnderlyingJournalAtomically() {
        val requestId = createRequest()
        assertTrue(helper.approveTransaction(requestId, checkerId, 11, "reviewed"))
        val row = helper.getPendingApprovals(11, "approved").getJSONObject(0)
        assertEquals(requestId, row.getLong("id"))
        assertEquals("approved", row.getString("status"))
        assertEquals(1, row.getInt("approvals_count"))
        helper.writableDatabase.rawQuery(
            "SELECT status FROM journal_entries WHERE id=? AND station_id=11",
            arrayOf(journalId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("posted", cursor.getString(0))
        }
        try {
            helper.approveTransaction(requestId, checkerId, 11, "duplicate retry")
            throw AssertionError("يجب رفض إعادة تنفيذ طلب اعتماد مكتمل")
        } catch (_: IllegalArgumentException) {
            // The completed request is no longer eligible for execution.
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun userFromAnotherStationCannotApproveRequest() {
        helper.approveTransaction(createRequest(), otherStationUserId, 11, "wrong station")
    }

    @Test
    fun approvedJournalReversalCreatesTheReversalEntryAndUpdatesOriginal() {
        val postApprovalId = createRequest()
        assertTrue(helper.rejectTransaction(postApprovalId, checkerId, 11, "تهيئة قيد مرحل للاختبار"))
        assertEquals(1, helper.postJournalEntry(journalId, makerId, 11))

        val reverseApprovalId = helper.createPendingApproval(
            11, "journal", journalId, "reverse", 6000.0, makerId
        )
        assertTrue(helper.approveTransaction(reverseApprovalId, checkerId, 11, "عكس معتمد للاختبار"))

        helper.writableDatabase.rawQuery(
            "SELECT status,reversed_entry_id FROM journal_entries WHERE id=? AND station_id=11",
            arrayOf(journalId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("reversed", cursor.getString(0))
            val reversalId = cursor.getLong(1)
            assertTrue(reversalId > 0L)
            helper.writableDatabase.rawQuery(
                "SELECT status,reference_type,reference_id FROM journal_entries WHERE id=? AND station_id=11",
                arrayOf(reversalId.toString())
            ).use { reversal ->
                assertTrue(reversal.moveToFirst())
                assertEquals("posted", reversal.getString(0))
                assertEquals("reversal", reversal.getString(1))
                assertEquals(journalId, reversal.getLong(2))
            }
        }
    }

    @Test
    fun rejectedRequestRequiresReasonAndRecordsDecision() {
        val requestId = createRequest()
        assertTrue(helper.rejectTransaction(requestId, checkerId, 11, "مستندات غير مكتملة"))
        val row = helper.getPendingApprovals(11, "rejected").getJSONObject(0)
        assertEquals("rejected", row.getString("status"))
        assertEquals("مستندات غير مكتملة", row.getString("rejection_reason"))
        assertNotEquals(makerId, checkerId)
        helper.writableDatabase.rawQuery(
            "SELECT status FROM journal_entries WHERE id=? AND station_id=11",
            arrayOf(journalId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("draft", cursor.getString(0))
        }
    }

    @Test
    fun approvedPaymentReversalRestoresCashAndReversesJournalExactlyOnce() {
        ensureFinancialFixtures()
        val paymentId = helper.addPayment(
            JSONObject().put("amount", 6000.0).put("cash_box_id", cashBoxId)
                .put("payment_type", "cash").put("payment_method", "cash").put("notes", "maker-checker test"),
            11, makerId
        )
        assertEquals(4000.0, cashBalance(), 0.001)
        val journalId = helper.writableDatabase.rawQuery(
            "SELECT journal_entry_id FROM payments WHERE id=? AND station_id=11", arrayOf(paymentId.toString())
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }
        assertTrue(journalId > 0L)
        val queued = helper.requestOrReversePaymentRecord(paymentId, "سبب العكس للاختبار", 11, makerId)
        assertTrue(queued.optBoolean("requires_approval"))
        val approvalId = queued.getLong("approval_id")
        assertEquals(4000.0, cashBalance(), 0.001)
        assertTrue(helper.approveTransaction(approvalId, checkerId, 11, "اعتماد عكس الدفعة"))
        assertEquals(10000.0, cashBalance(), 0.001)
        assertEquals(1, countRows(
            "SELECT COUNT(*) FROM cash_movements WHERE reference_type='payment_reversal' AND reference_id=? AND is_deleted=0",
            arrayOf(paymentId.toString())
        ))
        val status = helper.writableDatabase.rawQuery(
            "SELECT status FROM payments WHERE id=? AND station_id=11", arrayOf(paymentId.toString())
        ).use { c -> check(c.moveToFirst()); c.getString(0) }
        assertEquals("refunded", status)
        assertEquals(1, countRows(
            "SELECT COUNT(*) FROM journal_entries WHERE reference_type='reversal' AND reference_id=? AND station_id=11 AND is_deleted=0",
            arrayOf(journalId.toString())
        ))
        try {
            helper.approveTransaction(approvalId, checkerId, 11, "إعادة محاولة")
            throw AssertionError("لا يجوز تنفيذ عكس الدفعة مرتين")
        } catch (_: IllegalArgumentException) {
            // The completed request must not execute again.
        }
    }

    @Test
    fun approvedExpenseReversalRestoresCashAndReversesJournalExactlyOnce() {
        ensureFinancialFixtures()
        val expenseId = helper.addExpense(
            JSONObject().put("amount", 6000.0).put("expense_category_id", expenseCategoryId)
                .put("payee_name", "Test supplier").put("description", "Maker-checker test expense")
                .put("cash_box_id", cashBoxId),
            11, makerId
        )
        assertEquals(4000.0, cashBalance(), 0.001)
        val journalId = helper.writableDatabase.rawQuery(
            "SELECT journal_entry_id FROM expenses WHERE id=? AND station_id=11", arrayOf(expenseId.toString())
        ).use { c -> check(c.moveToFirst()); c.getLong(0) }
        assertTrue(journalId > 0L)
        val queued = helper.requestOrReverseExpenseRecord(expenseId, "سبب العكس للاختبار", 11, makerId)
        assertTrue(queued.optBoolean("requires_approval"))
        val approvalId = queued.getLong("approval_id")
        assertEquals(4000.0, cashBalance(), 0.001)
        assertTrue(helper.approveTransaction(approvalId, checkerId, 11, "اعتماد عكس المصروف"))
        assertEquals(10000.0, cashBalance(), 0.001)
        assertEquals(1, countRows(
            "SELECT COUNT(*) FROM cash_movements WHERE reference_type='expense_reversal' AND reference_id=? AND is_deleted=0",
            arrayOf(expenseId.toString())
        ))
        val status = helper.writableDatabase.rawQuery(
            "SELECT status,payment_status FROM expenses WHERE id=? AND station_id=11", arrayOf(expenseId.toString())
        ).use { c -> check(c.moveToFirst()); c.getString(0) to c.getString(1) }
        assertEquals("reversed", status.first)
        assertEquals("reversed", status.second)
        assertEquals(1, countRows(
            "SELECT COUNT(*) FROM journal_entries WHERE reference_type='reversal' AND reference_id=? AND station_id=11 AND is_deleted=0",
            arrayOf(journalId.toString())
        ))
        try {
            helper.approveTransaction(approvalId, checkerId, 11, "إعادة محاولة")
            throw AssertionError("لا يجوز تنفيذ عكس المصروف مرتين")
        } catch (_: IllegalArgumentException) {
            // The completed request must not execute again.
        }
    }

}
