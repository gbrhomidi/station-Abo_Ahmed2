package com.aistudio.dieselstationsms.kxmpzq

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoricalFinancialReconciliationRepairTest {
    private lateinit var db: SQLiteDatabase

    @Before fun setUp() {
        db=SQLiteDatabase.create(null)
        db.execSQL("CREATE TABLE party_types(id INTEGER PRIMARY KEY,type_code TEXT)")
        db.execSQL("""CREATE TABLE parties(id INTEGER PRIMARY KEY,station_id INTEGER,party_type_id INTEGER,legal_name TEXT,
            current_balance REAL DEFAULT 0,total_due REAL DEFAULT 0,total_purchases REAL DEFAULT 0,total_payments REAL DEFAULT 0,is_deleted INTEGER DEFAULT 0)""")
        db.execSQL("CREATE TABLE customer_ledger(id INTEGER PRIMARY KEY,party_id INTEGER,transaction_date TEXT,debit REAL,credit REAL,balance REAL,transaction_type TEXT,transaction_id INTEGER)")
        db.execSQL("CREATE TABLE supplier_ledger(id INTEGER PRIMARY KEY,party_id INTEGER,transaction_date TEXT,debit REAL,credit REAL,balance REAL,transaction_type TEXT,transaction_id INTEGER)")
        db.execSQL("""CREATE TABLE products(id INTEGER PRIMARY KEY,station_id INTEGER,product_name TEXT,product_name_ar TEXT,
            quantity REAL DEFAULT 0,is_deleted INTEGER DEFAULT 0)""")
        db.execSQL("CREATE TABLE inventory_levels(id INTEGER PRIMARY KEY,product_id INTEGER,quantity_on_hand REAL,is_deleted INTEGER DEFAULT 0)")
        db.execSQL("""CREATE TABLE sales_transactions(id INTEGER PRIMARY KEY,station_id INTEGER,sale_code TEXT,status TEXT,is_deleted INTEGER DEFAULT 0,
            net_amount REAL,paid_amount REAL,remaining_amount REAL)""")
        db.execSQL("INSERT INTO party_types(id,type_code) VALUES(1,'CUSTOMER'),(2,'SUPPLIER')")
    }
    @After fun tearDown(){db.close()}

    @Test fun repairsDerivedLedgersInventoryAndRemainingOnlyForTargetStation() {
        db.execSQL("INSERT INTO parties(id,station_id,party_type_id,legal_name,current_balance,total_due,total_purchases,total_payments) VALUES(1,1,1,'عميل',900,0,0,0),(2,1,2,'مورد',900,1,1,0),(3,2,1,'محطة أخرى',777,0,0,0)")
        db.execSQL("INSERT INTO customer_ledger(id,party_id,transaction_date,debit,credit,balance) VALUES(1,1,'2026-01-01',100,0,999),(2,1,'2026-01-02',0,25,999)")
        db.execSQL("INSERT INTO supplier_ledger(id,party_id,transaction_date,debit,credit,balance,transaction_type,transaction_id) VALUES(1,2,'2026-01-01',0,250,999,'fuel_supply_credit',11),(2,2,'2026-01-02',40,0,999,'supplier_payment',12)")
        db.execSQL("INSERT INTO products(id,station_id,product_name,product_name_ar,quantity) VALUES(1,1,'Oil','زيت',999),(2,2,'Other','آخر',777)")
        db.execSQL("INSERT INTO inventory_levels(id,product_id,quantity_on_hand) VALUES(1,1,12),(2,1,8),(3,2,5)")
        db.execSQL("INSERT INTO sales_transactions(id,station_id,sale_code,status,net_amount,paid_amount,remaining_amount) VALUES(1,1,'S1','completed',100,30,80),(2,2,'S2','completed',100,10,90)")
        val result=HistoricalFinancialReconciliationRepair(db).run(1,77L)
        assertTrue(result.getBoolean("success"))
        assertEquals(75.0, scalar("SELECT current_balance FROM parties WHERE id=1"),0.001)
        assertEquals(210.0, scalar("SELECT current_balance FROM parties WHERE id=2"),0.001)
        assertEquals(210.0, scalar("SELECT total_due FROM parties WHERE id=2"),0.001)
        assertEquals(250.0, scalar("SELECT total_purchases FROM parties WHERE id=2"),0.001)
        assertEquals(40.0, scalar("SELECT total_payments FROM parties WHERE id=2"),0.001)
        assertEquals(20.0, scalar("SELECT quantity FROM products WHERE id=1"),0.001)
        assertEquals(70.0, scalar("SELECT remaining_amount FROM sales_transactions WHERE id=1"),0.001)
        assertEquals(777.0, scalar("SELECT current_balance FROM parties WHERE id=3"),0.001)
        assertEquals(777.0, scalar("SELECT quantity FROM products WHERE id=2"),0.001)
        assertTrue(scalar("SELECT COUNT(*) FROM financial_reconciliation_actions WHERE run_id="+result.getLong("run_id")+" AND status='repaired'")>=5)
    }
    @Test fun stationOneRepairDoesNotClearAnotherStationsCashboxMapping() {
        db.execSQL("CREATE TABLE cash_boxes(id INTEGER PRIMARY KEY,station_id INTEGER,is_deleted INTEGER DEFAULT 0)")
        db.execSQL("""CREATE TABLE accounts(id INTEGER PRIMARY KEY,station_id INTEGER,account_code TEXT,account_type TEXT,
            is_active INTEGER DEFAULT 1,is_deleted INTEGER DEFAULT 0,cash_box_id INTEGER,bank_account_id INTEGER,
            is_cash_account INTEGER DEFAULT 0,is_bank_account INTEGER DEFAULT 0,opening_balance REAL DEFAULT 0,parent_account_id INTEGER)""")
        db.execSQL("INSERT INTO cash_boxes(id,station_id,is_deleted) VALUES(22,2,0)")
        db.execSQL("INSERT INTO accounts(id,station_id,account_code,account_type,cash_box_id,is_cash_account) VALUES(12,1,'1102','asset',22,1)")
        HistoricalFinancialReconciliationRepair(db).run(1,77L)
        assertEquals(22.0, scalar("SELECT cash_box_id FROM accounts WHERE id=12"),0.001)
        assertEquals(1.0, scalar("SELECT is_cash_account FROM accounts WHERE id=12"),0.001)
    }

    @Test fun clearsOnlyExactParentOpeningDuplicateAndPreservesChildOpenings() {
        db.execSQL("""CREATE TABLE accounts(
            id INTEGER PRIMARY KEY,station_id INTEGER,account_code TEXT,account_type TEXT,
            is_active INTEGER DEFAULT 1,is_deleted INTEGER DEFAULT 0,
            opening_balance REAL DEFAULT 0,parent_account_id INTEGER)""")
        db.execSQL("INSERT INTO accounts(id,station_id,account_code,account_type,opening_balance,parent_account_id) VALUES" +
            "(1,1,'1000','asset',300,NULL),(2,1,'1001','asset',100,1),(3,1,'1002','asset',200,1)," +
            "(4,1,'2000','liability',500,NULL),(5,1,'2001','liability',200,4)")
        val result=HistoricalFinancialReconciliationRepair(db).run(1,77L)
        assertTrue(result.getBoolean("success"))
        assertEquals(0.0,scalar("SELECT opening_balance FROM accounts WHERE id=1"),0.001)
        assertEquals(100.0,scalar("SELECT opening_balance FROM accounts WHERE id=2"),0.001)
        assertEquals(200.0,scalar("SELECT opening_balance FROM accounts WHERE id=3"),0.001)
        // Unequal parent/child totals remain untouched and are recorded as ambiguous.
        assertEquals(500.0,scalar("SELECT opening_balance FROM accounts WHERE id=4"),0.001)
        assertEquals(200.0,scalar("SELECT opening_balance FROM accounts WHERE id=5"),0.001)
        assertTrue(scalar("SELECT COUNT(*) FROM financial_reconciliation_actions WHERE action_code='clear_duplicate_parent_opening' AND status='repaired'")>=1)
        assertTrue(scalar("SELECT COUNT(*) FROM financial_reconciliation_actions WHERE action_code='parent_opening_allocation_ambiguous' AND status='skipped'")>=1)
    }

    @Test fun repairsOnlyCashAndBankBalancesProvenByConsistentLedgers() {
        db.execSQL("""CREATE TABLE cash_boxes(id INTEGER PRIMARY KEY,station_id INTEGER,opening_balance REAL,
            current_balance REAL,is_deleted INTEGER DEFAULT 0)""")
        db.execSQL("""CREATE TABLE cash_movements(id INTEGER PRIMARY KEY,station_id INTEGER,cash_box_id INTEGER,
            movement_type TEXT,amount REAL,balance_before REAL,balance_after REAL,status TEXT,is_deleted INTEGER DEFAULT 0)""")
        db.execSQL("""CREATE TABLE bank_accounts(id INTEGER PRIMARY KEY,station_id INTEGER,opening_balance REAL,
            current_balance REAL,is_deleted INTEGER DEFAULT 0)""")
        db.execSQL("""CREATE TABLE bank_ledger(id INTEGER PRIMARY KEY,station_id INTEGER,bank_account_id INTEGER,
            debit REAL,credit REAL,balance REAL,status TEXT,is_deleted INTEGER DEFAULT 0)""")
        db.execSQL("INSERT INTO cash_boxes(id,station_id,opening_balance,current_balance) VALUES(1,1,100,0),(2,1,100,25)")
        db.execSQL("INSERT INTO cash_movements(id,station_id,cash_box_id,movement_type,amount,balance_before,balance_after,status) VALUES" +
            "(1,1,1,'in',50,100,150,'completed'),(2,1,1,'out',20,150,130,'posted')," +
            "(3,1,2,'transfer',75,100,25,'completed')")
        db.execSQL("INSERT INTO bank_accounts(id,station_id,opening_balance,current_balance) VALUES(1,1,50,-500),(2,1,100,0)")
        db.execSQL("INSERT INTO bank_ledger(id,station_id,bank_account_id,debit,credit,balance,status) VALUES" +
            "(1,1,1,25,0,75,'posted'),(2,1,1,0,10,65,'completed'),(3,1,2,10,0,999,'posted')")
        val result=HistoricalFinancialReconciliationRepair(db).run(1,77L)
        assertTrue(result.getBoolean("success"))
        assertEquals(130.0,scalar("SELECT current_balance FROM cash_boxes WHERE id=1"),0.001)
        assertEquals(25.0,scalar("SELECT current_balance FROM cash_boxes WHERE id=2"),0.001)
        assertEquals(65.0,scalar("SELECT current_balance FROM bank_accounts WHERE id=1"),0.001)
        assertEquals(0.0,scalar("SELECT current_balance FROM bank_accounts WHERE id=2"),0.001)
        assertTrue(scalar("SELECT COUNT(*) FROM financial_reconciliation_actions WHERE action_code='cash_movement_chain_ambiguous' AND status='skipped'")>=1)
        assertTrue(scalar("SELECT COUNT(*) FROM financial_reconciliation_actions WHERE action_code='bank_ledger_chain_ambiguous' AND status='skipped'")>=1)
    }

    @Test fun incompleteSchemaBlocksFinancialStatementApproval() {
        val result = HistoricalFinancialReconciliationAudit(db).run(1)
        assertEquals(false, result.getBoolean("ready_for_financial_statement_approval"))
        val issues = result.getJSONArray("issues")
        assertTrue((0 until issues.length()).any {
            issues.getJSONObject(it).optString("code") == "historical_schema_incomplete"
        })
    }

    private fun scalar(sql:String)=db.rawQuery(sql,null).use{c->if(c.moveToFirst())c.getDouble(0)else Double.NaN}
}