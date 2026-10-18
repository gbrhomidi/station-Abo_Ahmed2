package com.aistudio.dieselstationsms.kxmpzq

import android.database.sqlite.SQLiteDatabase
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
class FinancialIntegrityAuditorTest {
    private lateinit var db: SQLiteDatabase
    private lateinit var auditor: FinancialIntegrityAuditor

    @Before
    fun setUp() {
        db = SQLiteDatabase.create(null)
        db.execSQL("""CREATE TABLE journal_entries (
            id INTEGER PRIMARY KEY, station_id INTEGER NOT NULL, status TEXT NOT NULL,
            total_debit REAL NOT NULL DEFAULT 0, total_credit REAL NOT NULL DEFAULT 0,
            reference_type TEXT, reference_id INTEGER, reversed_entry_id INTEGER,
            is_deleted INTEGER NOT NULL DEFAULT 0, entry_date TEXT
        )""")
        db.execSQL("""CREATE TABLE journal_entry_items (
            id INTEGER PRIMARY KEY, journal_entry_id INTEGER NOT NULL, account_id INTEGER NOT NULL,
            debit REAL NOT NULL DEFAULT 0, credit REAL NOT NULL DEFAULT 0
        )""")
        db.execSQL("""CREATE TABLE cash_boxes (
            id INTEGER PRIMARY KEY, station_id INTEGER NOT NULL, opening_balance REAL NOT NULL,
            current_balance REAL NOT NULL, is_deleted INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("""CREATE TABLE cash_movements (
            id INTEGER PRIMARY KEY, cash_box_id INTEGER NOT NULL, movement_type TEXT NOT NULL,
            amount REAL NOT NULL, balance_before REAL, balance_after REAL,
            is_deleted INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("""CREATE TABLE bank_accounts (
            id INTEGER PRIMARY KEY, station_id INTEGER NOT NULL, opening_balance REAL NOT NULL,
            current_balance REAL NOT NULL, is_deleted INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("""CREATE TABLE bank_ledger (
            id INTEGER PRIMARY KEY, bank_account_id INTEGER NOT NULL, debit REAL NOT NULL DEFAULT 0,
            credit REAL NOT NULL DEFAULT 0, balance REAL NOT NULL
        )""")
        auditor = FinancialIntegrityAuditor(db)
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun reconcilesCashBoxAgainstOpeningBalanceAndMovementLedger() {
        db.execSQL("INSERT INTO cash_boxes(id,station_id,opening_balance,current_balance) VALUES(1,1,100,115)")
        db.execSQL("INSERT INTO cash_movements(id,cash_box_id,movement_type,amount,balance_before,balance_after) VALUES(1,1,'in',20,100,120)")
        db.execSQL("INSERT INTO cash_movements(id,cash_box_id,movement_type,amount,balance_before,balance_after) VALUES(2,1,'out',5,120,115)")

        val result = auditor.audit(1)
        val checked = result.getJSONObject("checked")
        val boxes = checked.getJSONArray("cash_box_reconciliation")
        assertEquals(1, boxes.length())
        assertTrue(boxes.getJSONObject(0).getBoolean("reconciled"))
        assertEquals(115.0, boxes.getJSONObject(0).getDouble("expected_balance_from_movements"), 0.001)
        assertEquals(0, checked.getInt("cash_box_reconciliation_issue_count"))
    }

    @Test
    fun detectsCashBalanceDriftAndBrokenMovementChainWithoutRepairingIt() {
        db.execSQL("INSERT INTO cash_boxes(id,station_id,opening_balance,current_balance) VALUES(1,1,100,125)")
        db.execSQL("INSERT INTO cash_movements(id,cash_box_id,movement_type,amount,balance_before,balance_after) VALUES(1,1,'in',20,100,120)")
        db.execSQL("INSERT INTO cash_movements(id,cash_box_id,movement_type,amount,balance_before,balance_after) VALUES(2,1,'out',5,119,114)")

        val result = auditor.audit(1)
        val box = result.getJSONObject("checked").getJSONArray("cash_box_reconciliation").getJSONObject(0)
        assertFalse(box.getBoolean("reconciled"))
        assertEquals(125.0, box.getDouble("current_balance"), 0.001)
        assertEquals(115.0, box.getDouble("expected_balance_from_movements"), 0.001)
        assertTrue(box.getInt("broken_movement_chain_count") > 0)
        assertTrue(result.getJSONArray("issues").toString().contains("cash_box_reconciliation_mismatch"))
    }

    @Test
    fun ignoresOtherStationsCashBoxesAndDetectsUnknownMovementTypes() {
        db.execSQL("INSERT INTO cash_boxes(id,station_id,opening_balance,current_balance) VALUES(1,1,10,10)")
        db.execSQL("INSERT INTO cash_boxes(id,station_id,opening_balance,current_balance) VALUES(2,2,10,999)")
        db.execSQL("INSERT INTO cash_movements(id,cash_box_id,movement_type,amount,balance_before,balance_after) VALUES(1,2,'in',5,10,15)")
        db.execSQL("INSERT INTO cash_movements(id,cash_box_id,movement_type,amount,balance_before,balance_after) VALUES(2,1,'manual_adjustment',3,10,13)")

        val result = auditor.audit(1)
        val boxes = result.getJSONObject("checked").getJSONArray("cash_box_reconciliation")
        assertEquals(1, boxes.length())
        assertEquals(1, boxes.getJSONObject(0).getInt("unknown_movement_types"))
        assertTrue(result.getJSONArray("issues").toString().contains("cash_box_reconciliation_mismatch"))
    }

    @Test
    fun acceptsReciprocalReversalAndDetectsBrokenOrDuplicateLinks() {
        db.execSQL("""INSERT INTO journal_entries(id,station_id,status,total_debit,total_credit,reference_type,reference_id,reversed_entry_id,is_deleted,entry_date)
            VALUES(1,1,'reversed',10,10,'payment',77,2,0,'2026-10-01')""")
        db.execSQL("""INSERT INTO journal_entries(id,station_id,status,total_debit,total_credit,reference_type,reference_id,reversed_entry_id,is_deleted,entry_date)
            VALUES(2,1,'posted',10,10,'reversal',1,1,0,'2026-10-02')""")
        db.execSQL("INSERT INTO journal_entry_items(id,journal_entry_id,account_id,debit,credit) VALUES(1,1,100,10,0)")
        db.execSQL("INSERT INTO journal_entry_items(id,journal_entry_id,account_id,debit,credit) VALUES(2,1,200,0,10)")
        db.execSQL("INSERT INTO journal_entry_items(id,journal_entry_id,account_id,debit,credit) VALUES(3,2,100,0,10)")
        db.execSQL("INSERT INTO journal_entry_items(id,journal_entry_id,account_id,debit,credit) VALUES(4,2,200,10,0)")

        val clean = auditor.audit(1).getJSONObject("checked")
        assertEquals(0, clean.getInt("invalid_reversal_link_count"))

        db.execSQL("UPDATE journal_entries SET reversed_entry_id=99 WHERE id=1")
        db.execSQL("""INSERT INTO journal_entries(id,station_id,status,total_debit,total_credit,reference_type,reference_id,reversed_entry_id,is_deleted,entry_date)
            VALUES(3,1,'posted',10,10,'reversal',1,1,0,'2026-10-03')""")
        db.execSQL("INSERT INTO journal_entry_items(id,journal_entry_id,account_id,debit,credit) VALUES(5,3,100,0,10)")
        db.execSQL("INSERT INTO journal_entry_items(id,journal_entry_id,account_id,debit,credit) VALUES(6,3,200,10,0)")

        val damaged = auditor.audit(1)
        val checked = damaged.getJSONObject("checked")
        assertTrue(checked.getInt("invalid_reversal_link_count") > 0)
        assertTrue(checked.getJSONArray("duplicate_posted_reversals").length() > 0)
        assertTrue(damaged.getJSONArray("issues").toString().contains("invalid_reversal_links"))
    }

    @Test
    fun reversalAuditIsStationScopedAndRejectsCrossStationPairs() {
        db.execSQL("""INSERT INTO journal_entries(id,station_id,status,total_debit,total_credit,reference_type,reference_id,reversed_entry_id,is_deleted,entry_date)
            VALUES(1,1,'reversed',10,10,'payment',7,2,0,'2026-10-01')""")
        db.execSQL("""INSERT INTO journal_entries(id,station_id,status,total_debit,total_credit,reference_type,reference_id,reversed_entry_id,is_deleted,entry_date)
            VALUES(2,2,'posted',10,10,'reversal',1,1,0,'2026-10-02')""")
        db.execSQL("INSERT INTO journal_entry_items(id,journal_entry_id,account_id,debit,credit) VALUES(1,2,100,10,0)")
        db.execSQL("INSERT INTO journal_entry_items(id,journal_entry_id,account_id,debit,credit) VALUES(2,2,200,0,10)")

        val result = auditor.audit(1)
        assertTrue(result.getJSONObject("checked").getInt("invalid_reversal_link_count") > 0)
    }

    @Test
    fun detectsReversalAccountLineMismatchWithoutChangingPostedEntries() {
        db.execSQL("""INSERT INTO journal_entries(id,station_id,status,total_debit,total_credit,reference_type,reference_id,reversed_entry_id,is_deleted,entry_date)
            VALUES(10,1,'reversed',10,10,'payment',88,11,0,'2026-10-01')""")
        db.execSQL("""INSERT INTO journal_entries(id,station_id,status,total_debit,total_credit,reference_type,reference_id,reversed_entry_id,is_deleted,entry_date)
            VALUES(11,1,'posted',10,10,'reversal',10,10,0,'2026-10-02')""")
        db.execSQL("INSERT INTO journal_entry_items(id,journal_entry_id,account_id,debit,credit) VALUES(10,10,100,10,0)")
        db.execSQL("INSERT INTO journal_entry_items(id,journal_entry_id,account_id,debit,credit) VALUES(11,10,200,0,10)")
        db.execSQL("INSERT INTO journal_entry_items(id,journal_entry_id,account_id,debit,credit) VALUES(12,11,100,0,9)")
        db.execSQL("INSERT INTO journal_entry_items(id,journal_entry_id,account_id,debit,credit) VALUES(13,11,200,10,0)")

        val result = auditor.audit(1)
        val checked = result.getJSONObject("checked")
        val mismatches = checked.getJSONArray("reversal_item_mismatches")
        assertEquals(1, mismatches.length())
        assertEquals(100L, mismatches.getJSONObject(0).getLong("account_id"))
        assertTrue(checked.getInt("invalid_reversal_link_count") > 0)
        assertTrue(result.getJSONArray("issues").toString().contains("invalid_reversal_links"))

        // The integrity auditor is diagnostic only; it must not rewrite either side.
        db.rawQuery("SELECT debit,credit FROM journal_entry_items WHERE id=12", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0.0, c.getDouble(0), 0.001)
            assertEquals(9.0, c.getDouble(1), 0.001)
        }
        db.rawQuery("SELECT status,reversed_entry_id FROM journal_entries WHERE id=10", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("reversed", c.getString(0))
            assertEquals(11L, c.getLong(1))
        }
    }

    @Test
    fun reconcilesBankAccountAgainstOpeningBalanceAndBankLedger() {
        db.execSQL("INSERT INTO bank_accounts(id,station_id,opening_balance,current_balance) VALUES(1,1,100,115)")
        db.execSQL("INSERT INTO bank_ledger(id,bank_account_id,debit,credit,balance) VALUES(1,1,20,0,120)")
        db.execSQL("INSERT INTO bank_ledger(id,bank_account_id,debit,credit,balance) VALUES(2,1,0,5,115)")

        val result = auditor.audit(1)
        val checked = result.getJSONObject("checked")
        val accounts = checked.getJSONArray("bank_account_reconciliation")
        assertEquals(1, accounts.length())
        assertTrue(accounts.getJSONObject(0).getBoolean("reconciled"))
        assertEquals(115.0, accounts.getJSONObject(0).getDouble("expected_balance_from_ledger"), 0.001)
        assertEquals(0, checked.getInt("bank_account_reconciliation_issue_count"))
    }

    @Test
    fun detectsBankLedgerDriftAndBrokenChainButIgnoresOtherStations() {
        db.execSQL("INSERT INTO bank_accounts(id,station_id,opening_balance,current_balance) VALUES(1,1,100,130)")
        db.execSQL("INSERT INTO bank_accounts(id,station_id,opening_balance,current_balance) VALUES(2,2,100,999)")
        db.execSQL("INSERT INTO bank_ledger(id,bank_account_id,debit,credit,balance) VALUES(1,1,20,0,120)")
        db.execSQL("INSERT INTO bank_ledger(id,bank_account_id,debit,credit,balance) VALUES(2,1,0,5,114)")
        db.execSQL("INSERT INTO bank_ledger(id,bank_account_id,debit,credit,balance) VALUES(3,2,50,0,150)")

        val result = auditor.audit(1)
        val checked = result.getJSONObject("checked")
        val accounts = checked.getJSONArray("bank_account_reconciliation")
        assertEquals(1, accounts.length())
        assertFalse(accounts.getJSONObject(0).getBoolean("reconciled"))
        assertEquals(115.0, accounts.getJSONObject(0).getDouble("expected_balance_from_ledger"), 0.001)
        assertTrue(accounts.getJSONObject(0).getInt("broken_ledger_chain_count") > 0)
        assertTrue(result.getJSONArray("issues").toString().contains("bank_account_reconciliation_mismatch"))

        db.rawQuery("SELECT current_balance FROM bank_accounts WHERE id=1", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(130.0, c.getDouble(0), 0.001)
        }
    }
}
