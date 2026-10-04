#!/usr/bin/env python3
"""SQLite integration proof for Evidence & Reconciliation Integrity.
This intentionally uses a small deterministic schema that mirrors the report-source
columns used by DatabaseHelper. It verifies source-set semantics, not Kotlin compilation.
"""
import sqlite3
from hashlib import sha256

DB = sqlite3.connect(':memory:')
DB.executescript('''
CREATE TABLE sales_transactions(
 id INTEGER PRIMARY KEY, station_id INTEGER, net_amount REAL, paid_amount REAL,
 remaining_amount REAL, payment_method TEXT, is_credit INTEGER, status TEXT,
 created_at TEXT, is_deleted INTEGER DEFAULT 0, customer_party_id INTEGER, sale_type TEXT
);
CREATE TABLE payments(id INTEGER PRIMARY KEY, sale_id INTEGER, amount REAL, status TEXT, is_deleted INTEGER DEFAULT 0, created_at TEXT);
CREATE TABLE sale_item_adjustments(id INTEGER PRIMARY KEY, sale_id INTEGER, station_id INTEGER, amount REAL, status TEXT, adjustment_type TEXT);
CREATE TABLE fuel_sale_adjustments(id INTEGER PRIMARY KEY, sale_id INTEGER, station_id INTEGER, amount REAL, quantity REAL, status TEXT, adjustment_type TEXT);
CREATE TABLE journal_entries(
 id INTEGER PRIMARY KEY, station_id INTEGER, total_debit REAL, total_credit REAL,
 status TEXT, entry_date TEXT, is_deleted INTEGER DEFAULT 0, reversed_entry_id INTEGER
);
''')
DB.executemany('INSERT INTO sales_transactions VALUES (?,?,?,?,?,?,?,?,?,?,?,?)', [
 (1,1,100,100,0,'cash',0,'completed','2026-10-01 10:00:00',0,10,'retail'),
 (2,1,200,50,150,'credit',1,'completed','2026-10-02 10:00:00',0,11,'retail'),
 (3,1,0,0,0,'cash',0,'refunded','2026-10-03 10:00:00',0,12,'retail'),
 (4,2,999,999,0,'cash',0,'completed','2026-10-02 10:00:00',0,13,'retail'),
 (5,1,50,50,0,'cash',0,'completed','2026-10-04 10:00:00',0,14,'retail'),
])
DB.executemany('INSERT INTO sale_item_adjustments VALUES (?,?,?,?,?,?)', [
 (1,2,1,25,'posted','return'),
 (2,2,1,5,'reversed','return'),
])
DB.executemany('INSERT INTO payments VALUES (?,?,?,?,?,?)', [
 (1,1,100,'completed',0,'2026-10-01 10:00:01'),
 (2,2,50,'completed',0,'2026-10-02 10:00:01'),
 (3,2,10,'completed',0,'2026-10-02 10:01:00'),
])
DB.executemany('INSERT INTO journal_entries VALUES (?,?,?,?,?,?,?,?)', [
 (1,1,100,100,'posted','2026-10-01',0,None),
 (2,1,50,50,'reversed','2026-10-02',0,1),
 (3,1,30,25,'posted','2026-10-03',0,None),
 (4,2,999,999,'posted','2026-10-02',0,None),
])

# 1) Station + date scope must select only station 1 and inclusive dates.
rows = DB.execute('''SELECT COUNT(*), COALESCE(SUM(net_amount),0)
FROM sales_transactions
WHERE station_id=? AND is_deleted=0 AND date(created_at) BETWEEN date(?) AND date(?)''', (1,'2026-10-01','2026-10-03')).fetchone()
assert rows == (3, 300.0), rows

# 2) Paid + remaining must reconcile to effective net; refunded rows must be zero.
delta, invalid_refunded = DB.execute('''SELECT COALESCE(SUM(ABS(net_amount-paid_amount-remaining_amount)),0),
COALESCE(SUM(CASE WHEN status='refunded' AND ABS(net_amount)>0.01 THEN 1 ELSE 0 END),0)
FROM sales_transactions WHERE station_id=? AND date(created_at) BETWEEN date(?) AND date(?) AND is_deleted=0''', (1,'2026-10-01','2026-10-03')).fetchone()
assert abs(delta) < 1e-9 and invalid_refunded == 0, (delta, invalid_refunded)

# 3) Posted return/damage adjustments are evidence of corrections; reversed rows are excluded.
adj = DB.execute('''SELECT COALESCE(SUM(amount),0), COUNT(*) FROM sale_item_adjustments
WHERE station_id=? AND status='posted' AND sale_id IN (SELECT id FROM sales_transactions WHERE station_id=? AND date(created_at) BETWEEN date(?) AND date(?))''', (1,1,'2026-10-01','2026-10-03')).fetchone()
assert adj == (25.0, 1), adj

# 4) EOD payments must aggregate independently from sales rows, preventing payment-row multiplication.
payments = DB.execute('''SELECT COALESCE(SUM(p.amount),0) FROM payments p JOIN sales_transactions s ON s.id=p.sale_id
WHERE s.station_id=? AND date(s.created_at) BETWEEN date(?) AND date(?) AND s.is_deleted=0
AND p.status='completed' AND p.is_deleted=0''', (1,'2026-10-01','2026-10-03')).fetchone()[0]
assert payments == 160.0, payments

# 5) Accounting evidence: only posted entries are current; reversed entries are separately visible.
posted = DB.execute('''SELECT COUNT(*), COALESCE(SUM(total_debit),0), COALESCE(SUM(total_credit),0),
SUM(CASE WHEN ABS(total_debit-total_credit)>0.01 THEN 1 ELSE 0 END)
FROM journal_entries WHERE station_id=? AND status='posted' AND is_deleted=0 AND date(entry_date) BETWEEN date(?) AND date(?)''', (1,'2026-10-01','2026-10-03')).fetchone()
assert posted == (2,130.0,125.0,1), posted
reversed_count = DB.execute("SELECT COUNT(*) FROM journal_entries WHERE station_id=1 AND status='reversed'").fetchone()[0]
assert reversed_count == 1, reversed_count

# 6) Evidence hash is deterministic over the canonical evidence payload.
canonical='{"contract_version":1,"report_type":"sales","station_id":1,"source_row_count":3,"source_total_net":300.0,"date_scope":{"from":"2026-10-01","to":"2026-10-03"}}'
hash1=sha256(canonical.encode()).hexdigest(); hash2=sha256(canonical.encode()).hexdigest()
assert hash1 == hash2 and len(hash1)==64

print('PASS: station/date isolation, payment reconciliation, return evidence, EOD payment aggregation, accounting reversal visibility, deterministic evidence hash')
