package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
class JournalStationIsolationRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        helper.writableDatabase
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun `journal operations are fail closed outside the authoritative station scope`() {
        val db = helper.writableDatabase
        val roleId = db.rawQuery("SELECT id FROM roles ORDER BY id LIMIT 1", null).use { cursor ->
            check(cursor.moveToFirst()) { "لا يوجد دور افتراضي للاختبار" }
            cursor.getLong(0)
        }
        insertStation(db, 11, "TEST-JRN-A", "محطة اختبار القيود أ")
        insertStation(db, 12, "TEST-JRN-B", "محطة اختبار القيود ب")
        val stationAUser = insertUser(db, "journal-scope-a", roleId, 11)
        val stationBUser = insertUser(db, "journal-scope-b", roleId, 12)
        val debitAccount = insertAccount(db, "T-JRN-DR", "حساب اختبار مدين")
        val creditAccount = insertAccount(db, "T-JRN-CR", "حساب اختبار دائن")

        val entryId = helper.saveJournalEntry(
            JSONObject()
                .put("entry_date", "2026-08-25")
                .put("description", "قيد عزل محطات فعلي")
                .put("entry_type", "general")
                .put("items", JSONArray()
                    .put(JSONObject().put("account_id", debitAccount).put("debit", 125.0).put("credit", 0.0))
                    .put(JSONObject().put("account_id", creditAccount).put("debit", 0.0).put("credit", 125.0))),
            stationAUser,
            11
        )

        assertEquals(1, helper.getJournalEntries(JSONObject(), 11).getInt("total"))
        assertEquals(0, helper.getJournalEntries(JSONObject(), 12).getInt("total"))
        assertNull(helper.getJournalEntryDetails(entryId, 12))

        try {
            helper.postJournalEntry(entryId, stationBUser, 12)
            throw AssertionError("لم يجب أن يستطيع مستخدم محطة B ترحيل قيد محطة A")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("القيد غير موجود"))
        }

        assertEquals(1, helper.postJournalEntry(entryId, stationAUser, 11))
        assertEquals(1, helper.getLedgerStats(11).getInt("total_entries"))
        assertEquals(0, helper.getLedgerStats(12).getInt("total_entries"))
        assertTrue(helper.getNextJournalEntryNumber(11).startsWith("JE-S11-"))
        assertTrue(helper.getNextJournalEntryNumber(12).startsWith("JE-S12-"))
    }

    @Test
    fun `v28 migration backfills a legacy journal only from a valid creator station`() {
        val db = helper.writableDatabase
        val roleId = db.rawQuery("SELECT id FROM roles ORDER BY id LIMIT 1", null).use { cursor ->
            check(cursor.moveToFirst()) { "لا يوجد دور افتراضي للاختبار" }
            cursor.getLong(0)
        }
        insertStation(db, 11, "TEST-MIG-A", "محطة اختبار الترحيل")
        val userId = insertUser(db, "journal-migration-user", roleId, 11)
        val debitAccount = insertAccount(db, "T-MIG-DR", "حساب ترحيل مدين")
        val creditAccount = insertAccount(db, "T-MIG-CR", "حساب ترحيل دائن")
        val entryId = helper.saveJournalEntry(
            JSONObject()
                .put("entry_date", "2026-08-25")
                .put("description", "قيد ترحيل قديم")
                .put("entry_type", "general")
                .put("items", JSONArray()
                    .put(JSONObject().put("account_id", debitAccount).put("debit", 20.0).put("credit", 0.0))
                    .put(JSONObject().put("account_id", creditAccount).put("debit", 0.0).put("credit", 20.0))),
            userId,
            11
        )
        db.update("journal_entries", ContentValues().apply { put("station_id", 0) }, "id = ?", arrayOf(entryId.toString()))
        db.execSQL("PRAGMA user_version = 27")

        DatabaseHelper.closeInstance()
        helper = DatabaseHelper.getInstance(context)
        assertEquals(1, helper.getJournalEntries(JSONObject(), 11).getInt("total"))
        assertEquals(0, helper.getJournalEntries(JSONObject(), 12).getInt("total"))
    }

    private fun insertUser(db: android.database.sqlite.SQLiteDatabase, username: String, roleId: Long, stationId: Int): Long =
        db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", username)
            put("password_hash", "test-only")
            put("full_name", username)
            put("role_id", roleId)
            put("station_id", stationId)
        })

    private fun insertStation(db: android.database.sqlite.SQLiteDatabase, id: Int, code: String, name: String) {
        db.insertOrThrow("stations", null, ContentValues().apply {
            put("id", id)
            put("uuid", UUID.randomUUID().toString())
            put("station_code", code)
            put("station_name", name)
            put("status", "active")
            put("is_deleted", 0)
        })
    }

    private fun insertAccount(db: android.database.sqlite.SQLiteDatabase, code: String, name: String): Long =
        db.insertOrThrow("accounts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("account_code", code)
            put("account_name", name)
            put("level", 1)
            put("account_type", "asset")
            put("normal_balance", "debit")
            put("is_active", 1)
            put("is_deleted", 0)
        })
}
