package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FiscalPeriodLifecycleRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private var userId = 0L
    private var debitAccountId = 0L
    private var creditAccountId = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        val db = helper.writableDatabase
        val roleId = db.rawQuery("SELECT id FROM roles ORDER BY id LIMIT 1", null).use { c ->
            check(c.moveToFirst()) { "لا يوجد دور افتراضي للاختبار" }; c.getLong(0)
        }
        userId = db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", "fiscal-period-${UUID.randomUUID().toString().take(8)}")
            put("password_hash", "test-only")
            put("full_name", "اختبار إقفال الفترات")
            put("role_id", roleId)
            put("station_id", 11)
            put("is_deleted", 0)
            put("status", "active")
        })
        val accounts = db.rawQuery("SELECT id FROM accounts WHERE COALESCE(is_deleted,0)=0 ORDER BY id LIMIT 2", null).use { c ->
            val values = mutableListOf<Long>()
            while (c.moveToNext()) values.add(c.getLong(0))
            values
        }
        check(accounts.isNotEmpty()) { "لا يوجد حساب لاختبار القيد" }
        debitAccountId = accounts[0]
        creditAccountId = accounts.getOrElse(1) { accounts[0] }
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    private fun saveDraft(date: String, description: String): Long =
        helper.saveJournalEntry(
            JSONObject()
                .put("entry_date", date)
                .put("description", description)
                .put("entry_type", "general")
                .put("items", JSONArray()
                    .put(JSONObject().put("account_id", debitAccountId).put("debit", 10.0).put("credit", 0.0))
                    .put(JSONObject().put("account_id", creditAccountId).put("debit", 0.0).put("credit", 10.0))),
            userId,
            11
        )

    @Test
    fun closeRequiresResolvedDraftsAndClosedPeriodBlocksJournalWritesUntilAuditedReopen() {
        val draftId = saveDraft("2026-08-10", "مسودة تمنع الإقفال")
        try {
            helper.closeFiscalPeriod(11, 2026, 8, userId, "إقفال شهري")
            throw AssertionError("كان يجب منع الإقفال مع وجود مسودة")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("مسودة"))
        }

        assertEquals(1, helper.deleteJournalEntry(draftId, userId, 11))
        val postedId = saveDraft("2026-08-12", "قيد مرحل قبل الإقفال")
        assertEquals(1, helper.postJournalEntry(postedId, userId, 11))
        val periodId = helper.closeFiscalPeriod(11, 2026, 8, userId, "مطابقة ومراجعة مكتملة")
        assertTrue(helper.isDateInClosedPeriod(11, "2026-08-15"))
        assertFalse(helper.isDateInClosedPeriod(12, "2026-08-15"))
        try {
            saveDraft("2026-08-15", "محاولة كتابة في فترة مغلقة")
            throw AssertionError("كان يجب أن يمنع SQLite الكتابة في الفترة المغلقة")
        } catch (expected: android.database.sqlite.SQLiteException) {
            assertTrue(expected.message.orEmpty().contains("FISCAL_PERIOD_CLOSED"))
        }
        try {
            helper.writableDatabase.delete("journal_entry_items", "journal_entry_id=?", arrayOf(postedId.toString()))
            throw AssertionError("كان يجب أن يمنع SQLite حذف بنود قيد مرحل في فترة مغلقة")
        } catch (expected: android.database.sqlite.SQLiteException) {
            assertTrue(expected.message.orEmpty().contains("FISCAL_PERIOD_CLOSED"))
        }

        assertTrue(helper.reopenFiscalPeriod(periodId, 11, userId, "تصحيح موثق بعد المراجعة"))
        assertFalse(helper.isDateInClosedPeriod(11, "2026-08-15"))
        saveDraft("2026-08-15", "كتابة بعد إعادة الفتح")
        val periods = helper.getFiscalPeriods(11, 2026)
        assertEquals(1, periods.length())
        assertEquals("reopened", periods.getJSONObject(0).getString("status"))
        assertEquals(2, periods.getJSONObject(0).getInt("audit_count"))
    }

    @Test
    fun closeAndReopenRequireReasonsAndCannotCrossStationScope() {
        val periodId = helper.closeFiscalPeriod(11, 2026, 7, userId, "إقفال اختبار")
        try {
            helper.reopenFiscalPeriod(periodId, 12, userId, "محاولة خارج المحطة")
            throw AssertionError("لا يجوز إعادة فتح فترة محطة أخرى")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("غير موجودة"))
        }
        try {
            helper.reopenFiscalPeriod(periodId, 11, userId, "   ")
            throw AssertionError("سبب إعادة الفتح إلزامي")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("سبب"))
        }
        assertTrue(helper.isDateInClosedPeriod(11, "2026-07-01"))
    }
}
