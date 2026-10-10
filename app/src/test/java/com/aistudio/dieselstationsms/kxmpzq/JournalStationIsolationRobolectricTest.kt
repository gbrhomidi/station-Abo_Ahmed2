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


    @Test
    fun `finance integrity snapshot supports both date boundaries`() {
        val db = helper.writableDatabase
        insertStation(db, 21, "TEST-FIN-RANGE", "محطة اختبار نطاق التقرير")
        val debitAccount = insertAccount(db, "T-FIN-RANGE-DR", "حساب اختبار مدين النطاق")
        val creditAccount = insertAccount(db, "T-FIN-RANGE-CR", "حساب اختبار دائن النطاق")
        val entryId = helper.saveJournalEntry(
            JSONObject()
                .put("entry_date", "2026-08-25")
                .put("description", "اختبار سلامة النطاق الزمني")
                .put("entry_type", "general")
                .put("items", JSONArray()
                    .put(JSONObject().put("account_id", debitAccount).put("debit", 35.0).put("credit", 0.0))
                    .put(JSONObject().put("account_id", creditAccount).put("debit", 0.0).put("credit", 35.0))),
            0L,
            21
        )
        assertEquals(1, helper.postJournalEntry(entryId, 0L, 21))

        val snapshot = helper.getFinanceIntegritySnapshot(21, "2026-08-25", "2026-08-25")
        assertEquals(35.0, snapshot.getDouble("journal_debit"), 0.000001)
        assertEquals(35.0, snapshot.getDouble("journal_credit"), 0.000001)
        assertEquals(0.0, snapshot.getDouble("journal_difference"), 0.000001)
    }


    @Test
    fun `completed payment linked to a draft journal is flagged as unreconciled`() {
        val db = helper.writableDatabase
        insertStation(db, 22, "TEST-FIN-LINK", "محطة اختبار ربط الدفعات")
        val debitAccount = insertAccount(db, "T-FIN-LINK-DR", "حساب اختبار مدين الربط")
        val creditAccount = insertAccount(db, "T-FIN-LINK-CR", "حساب اختبار دائن الربط")
        val draftJournalId = helper.saveJournalEntry(
            JSONObject()
                .put("entry_date", "2026-08-25")
                .put("description", "قيد مسودة لا يصلح لتسوية دفعة")
                .put("entry_type", "general")
                .put("items", JSONArray()
                    .put(JSONObject().put("account_id", debitAccount).put("debit", 12.0).put("credit", 0.0))
                    .put(JSONObject().put("account_id", creditAccount).put("debit", 0.0).put("credit", 12.0))),
            0L,
            22
        )
        db.insertOrThrow("payments", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("payment_code", "TEST-FIN-LINK-001")
            put("station_id", 22)
            put("journal_entry_id", draftJournalId)
            put("payment_type", "cash")
            put("payment_method", "cash")
            put("amount", 12.0)
            put("status", "completed")
            put("is_deleted", 0)
        })

        val snapshot = helper.getFinanceIntegritySnapshot(22, null, null)
        assertEquals(1L, snapshot.getLong("missing_payment_journals"))
        assertEquals(false, snapshot.getBoolean("is_reconciled"))
    }


    @Test
    fun `payment journal must match exact source id and amount`() {
        val db = helper.writableDatabase
        insertStation(db, 24, "TEST-FIN-MISMATCH", "محطة اختبار مطابقة القيد")
        val debitAccount = insertAccount(db, "T-FIN-MISMATCH-DR", "حساب مدين اختبار المطابقة")
        val creditAccount = insertAccount(db, "T-FIN-MISMATCH-CR", "حساب دائن اختبار المطابقة")
        val journalId = helper.saveJournalEntry(
            JSONObject()
                .put("entry_date", "2026-08-25")
                .put("description", "قيد مرجعه لا يطابق العملية")
                .put("entry_type", "general")
                .put("items", JSONArray()
                    .put(JSONObject().put("account_id", debitAccount).put("debit", 12.0).put("credit", 0.0))
                    .put(JSONObject().put("account_id", creditAccount).put("debit", 0.0).put("credit", 12.0))),
            0L,
            24
        )
        assertEquals(1, helper.postJournalEntry(journalId, 0L, 24))
        db.update("journal_entries", ContentValues().apply {
            put("reference_type", "receipt")
            put("reference_id", 999L)
        }, "id=?", arrayOf(journalId.toString()))
        db.insertOrThrow("payments", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("payment_code", "TEST-FIN-MISMATCH-001")
            put("station_id", 24)
            put("journal_entry_id", journalId)
            put("payment_type", "cash")
            put("payment_method", "cash")
            put("amount", 12.0)
            put("status", "completed")
            put("is_deleted", 0)
        })

        val snapshot = helper.getFinanceIntegritySnapshot(24, null, null)
        assertEquals(1L, snapshot.getLong("missing_payment_journals"))
        assertEquals(0.0, snapshot.getDouble("payment_journal_total"), 0.000001)
        assertEquals(false, snapshot.getBoolean("is_station_reconciled"))
    }


    @Test
    fun `posted finance journal without a valid source is reported as orphan`() {
        val db = helper.writableDatabase
        insertStation(db, 25, "TEST-FIN-ORPHAN-JRN", "محطة اختبار القيد اليتيم")
        val debitAccount = insertAccount(db, "T-FIN-ORPHAN-DR", "حساب مدين القيد اليتيم")
        val creditAccount = insertAccount(db, "T-FIN-ORPHAN-CR", "حساب دائن القيد اليتيم")
        val journalId = helper.saveJournalEntry(
            JSONObject()
                .put("entry_date", "2026-08-25")
                .put("description", "قيد دفع بلا عملية أصلية")
                .put("entry_type", "general")
                .put("items", JSONArray()
                    .put(JSONObject().put("account_id", debitAccount).put("debit", 19.0).put("credit", 0.0))
                    .put(JSONObject().put("account_id", creditAccount).put("debit", 0.0).put("credit", 19.0))),
            0L,
            25
        )
        assertEquals(1, helper.postJournalEntry(journalId, 0L, 25))
        db.update("journal_entries", ContentValues().apply {
            put("reference_type", "payment")
            put("reference_id", 999999L)
        }, "id=?", arrayOf(journalId.toString()))

        val snapshot = helper.getFinanceIntegritySnapshot(25, null, null)
        assertEquals(1L, snapshot.getLong("orphan_payment_journals"))
        assertEquals(1L, snapshot.getLong("orphan_finance_journals"))
        assertEquals(false, snapshot.getBoolean("is_station_reconciled"))
    }

    @Test
    fun `duplicate posted journals for one payment reference are reported`() {
        val db = helper.writableDatabase
        insertStation(db, 26, "TEST-FIN-DUP-JRN", "محطة اختبار القيود المكررة")
        val debitAccount = insertAccount(db, "T-FIN-DUP-DR", "حساب مدين القيود المكررة")
        val creditAccount = insertAccount(db, "T-FIN-DUP-CR", "حساب دائن القيود المكررة")

        fun createPostedJournal(description: String): Long {
            val id = helper.saveJournalEntry(
                JSONObject()
                    .put("entry_date", "2026-08-25")
                    .put("description", description)
                    .put("entry_type", "general")
                    .put("items", JSONArray()
                        .put(JSONObject().put("account_id", debitAccount).put("debit", 15.0).put("credit", 0.0))
                        .put(JSONObject().put("account_id", creditAccount).put("debit", 0.0).put("credit", 15.0))),
                0L,
                26
            )
            assertEquals(1, helper.postJournalEntry(id, 0L, 26))
            db.update("journal_entries", ContentValues().apply {
                put("reference_type", "payment")
                put("reference_id", 26001L)
            }, "id=?", arrayOf(id.toString()))
            return id
        }

        val linkedJournalId = createPostedJournal("القيد الصحيح للدفعة")
        createPostedJournal("قيد مكرر للدفعة")
        db.insertOrThrow("payments", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("payment_code", "TEST-FIN-DUP-001")
            put("station_id", 26)
            put("journal_entry_id", linkedJournalId)
            put("payment_type", "cash")
            put("payment_method", "cash")
            put("amount", 15.0)
            put("status", "completed")
            put("is_deleted", 0)
        })

        val snapshot = helper.getFinanceIntegritySnapshot(26, null, null)
        assertEquals(1L, snapshot.getLong("duplicate_finance_references"))
        assertEquals(false, snapshot.getBoolean("is_station_reconciled"))
    }

    @Test
    fun `valid posted reversal is not counted as orphan or duplicate finance journal`() {
        val db = helper.writableDatabase
        insertStation(db, 27, "TEST-FIN-REVERSAL", "محطة اختبار عكس القيود")
        val debitAccount = insertAccount(db, "T-FIN-REV-DR", "حساب مدين اختبار العكس")
        val creditAccount = insertAccount(db, "T-FIN-REV-CR", "حساب دائن اختبار العكس")
        val journalId = helper.saveJournalEntry(
            JSONObject()
                .put("entry_date", "2026-08-25")
                .put("description", "قيد دفعة سيجري عكسها")
                .put("entry_type", "general")
                .put("items", JSONArray()
                    .put(JSONObject().put("account_id", debitAccount).put("debit", 8.0).put("credit", 0.0))
                    .put(JSONObject().put("account_id", creditAccount).put("debit", 0.0).put("credit", 8.0))),
            0L,
            27
        )
        assertEquals(1, helper.postJournalEntry(journalId, 0L, 27))
        db.update("journal_entries", ContentValues().apply {
            put("reference_type", "payment")
            put("reference_id", 27001L)
        }, "id=?", arrayOf(journalId.toString()))
        val paymentId = db.insertOrThrow("payments", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("payment_code", "TEST-FIN-REV-001")
            put("station_id", 27)
            put("journal_entry_id", journalId)
            put("payment_type", "cash")
            put("payment_method", "cash")
            put("amount", 8.0)
            put("status", "completed")
            put("is_deleted", 0)
        })

        helper.reverseJournalEntry(journalId, "اختبار عكس مشروع", 0L, 27)
        db.update("payments", ContentValues().apply { put("status", "refunded") },
            "id=? AND station_id=27", arrayOf(paymentId.toString()))

        val snapshot = helper.getFinanceIntegritySnapshot(27, null, null)
        assertEquals(0L, snapshot.getLong("orphan_finance_journals"))
        assertEquals(0L, snapshot.getLong("duplicate_finance_references"))
        assertEquals(0L, snapshot.getLong("invalid_reversal_links"))
    }


    @Test
    fun `finance integrity reports orphan and duplicate posted operation references`() {
        val db = helper.writableDatabase
        insertStation(db, 25, "TEST-FIN-ORPHAN-DUP", "محطة اختبار القيود اليتيمة والمكررة")
        val debitAccount = insertAccount(db, "T-FIN-ORPHAN-DUP-DR", "حساب مدين اليتيم والمكرر")
        val creditAccount = insertAccount(db, "T-FIN-ORPHAN-DUP-CR", "حساب دائن اليتيم والمكرر")

        fun postedPaymentReference(description: String): Long {
            val id = helper.saveJournalEntry(
                JSONObject()
                    .put("entry_date", "2026-08-25")
                    .put("description", description)
                    .put("entry_type", "general")
                    .put("items", JSONArray()
                        .put(JSONObject().put("account_id", debitAccount).put("debit", 7.0).put("credit", 0.0))
                        .put(JSONObject().put("account_id", creditAccount).put("debit", 0.0).put("credit", 7.0))),
                0L,
                25
            )
            assertEquals(1, helper.postJournalEntry(id, 0L, 25))
            db.update("journal_entries", ContentValues().apply {
                put("reference_type", "payment")
                put("reference_id", 700L)
            }, "id=?", arrayOf(id.toString()))
            return id
        }

        val first = postedPaymentReference("القيد الأول للعملية")
        postedPaymentReference("قيد مكرر للمرجع نفسه")
        db.insertOrThrow("payments", null, ContentValues().apply {
            put("id", 700L)
            put("uuid", UUID.randomUUID().toString())
            put("payment_code", "TEST-FIN-ORPHAN-DUP-001")
            put("station_id", 25)
            put("journal_entry_id", first)
            put("payment_type", "cash")
            put("payment_method", "cash")
            put("amount", 7.0)
            put("status", "completed")
            put("is_deleted", 0)
        })
        val orphan = postedPaymentReference("قيد بمرجع لا توجد له عملية")
        db.update("journal_entries", ContentValues().apply {
            put("reference_id", 99999L)
        }, "id=?", arrayOf(orphan.toString()))

        val snapshot = helper.getFinanceIntegritySnapshot(25, null, null)
        assertEquals(1L, snapshot.getLong("orphan_referenced_journals"))
        assertEquals(1L, snapshot.getLong("duplicate_referenced_journal_groups"))
        assertEquals(false, snapshot.getBoolean("is_station_reconciled"))
    }

    @Test
    fun `valid posted reversal pair is not counted as orphan or duplicate operation journals`() {
        val db = helper.writableDatabase
        insertStation(db, 26, "TEST-FIN-REVERSAL", "محطة اختبار عكس القيود")
        val debitAccount = insertAccount(db, "T-FIN-REV-DR", "حساب مدين اختبار العكس")
        val creditAccount = insertAccount(db, "T-FIN-REV-CR", "حساب دائن اختبار العكس")
        val originalId = helper.saveJournalEntry(
            JSONObject()
                .put("entry_date", "2026-08-25")
                .put("description", "قيد أصلي لعكس صحيح")
                .put("entry_type", "general")
                .put("items", JSONArray()
                    .put(JSONObject().put("account_id", debitAccount).put("debit", 18.0).put("credit", 0.0))
                    .put(JSONObject().put("account_id", creditAccount).put("debit", 0.0).put("credit", 18.0))),
            0L,
            26
        )
        assertEquals(1, helper.postJournalEntry(originalId, 0L, 26))
        val reversalId = helper.reverseJournalEntry(originalId, "اختبار عكس صحيح", 0L, 26)
        assertTrue(reversalId > 0L)

        val snapshot = helper.getFinanceIntegritySnapshot(26, null, null)
        assertEquals(0L, snapshot.getLong("orphan_referenced_journals"))
        assertEquals(0L, snapshot.getLong("duplicate_referenced_journal_groups"))
        assertEquals(0L, snapshot.getLong("reversal_entries_without_origin"))

        // Exercise date-scoped self-join checks: both original and reversal are in range.
        val rangedSnapshot = helper.getFinanceIntegritySnapshot(26, "2026-01-01", "2027-01-01")
        assertEquals(0L, rangedSnapshot.getLong("invalid_posted_reversal_links"))
        assertEquals(0L, rangedSnapshot.getLong("reversed_finance_entries_without_valid_reversal"))
    }


    @Test
    fun `orphan payment is reported globally without falsely failing an unrelated station`() {
        val db = helper.writableDatabase
        insertStation(db, 23, "TEST-FIN-ORPHAN", "محطة اختبار السجلات اليتيمة")
        db.insertOrThrow("payments", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("payment_code", "TEST-FIN-ORPHAN-001")
            putNull("station_id")
            put("payment_type", "cash")
            put("payment_method", "cash")
            put("amount", 9.0)
            put("status", "completed")
            put("is_deleted", 0)
        })

        val snapshot = helper.getFinanceIntegritySnapshot(23, null, null)
        assertEquals(true, snapshot.getBoolean("is_station_reconciled"))
        assertEquals(true, snapshot.getBoolean("is_reconciled"))
        assertEquals(false, snapshot.getBoolean("is_global_integrity_clean"))
        assertEquals(1L, snapshot.getLong("orphan_payments"))
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
