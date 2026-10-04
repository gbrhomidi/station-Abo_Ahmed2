import sqlite3

DB = sqlite3.connect(':memory:')
DB.executescript('''
CREATE TABLE payments(id INTEGER PRIMARY KEY, station_id INTEGER, amount REAL, status TEXT, is_deleted INTEGER DEFAULT 0, journal_entry_id INTEGER);
CREATE TABLE receipts(id INTEGER PRIMARY KEY, station_id INTEGER, amount REAL, status TEXT, is_deleted INTEGER DEFAULT 0, journal_entry_id INTEGER);
CREATE TABLE expenses(id INTEGER PRIMARY KEY, station_id INTEGER, total_amount REAL, status TEXT, is_deleted INTEGER DEFAULT 0, journal_entry_id INTEGER);
CREATE TABLE journal_entries(id INTEGER PRIMARY KEY, station_id INTEGER, entry_date TEXT, total_debit REAL, total_credit REAL, status TEXT, is_deleted INTEGER DEFAULT 0, reference_type TEXT);
CREATE TABLE accounts(id INTEGER PRIMARY KEY, opening_balance REAL DEFAULT 0, is_deleted INTEGER DEFAULT 0);
CREATE TABLE journal_entry_items(id INTEGER PRIMARY KEY, journal_entry_id INTEGER, account_id INTEGER, debit REAL, credit REAL);
''')

DB.executemany('INSERT INTO payments VALUES (?,?,?,?,?,?)', [
    (1, 10, 100, 'completed', 0, 101),
    (2, 20, 900, 'completed', 0, 201),
])
DB.executemany('INSERT INTO receipts VALUES (?,?,?,?,?,?)', [
    (1, 10, 50, 'active', 0, 102),
    (2, 20, 800, 'active', 0, 202),
])
DB.executemany('INSERT INTO expenses VALUES (?,?,?,?,?,?)', [
    (1, 10, 30, 'paid', 0, 103),
    (2, 20, 700, 'paid', 0, 203),
])
DB.executemany('INSERT INTO journal_entries VALUES (?,?,?,?,?,?,?,?)', [
    (101,10,'2026-10-01',100,100,'posted',0,'payment'),
    (102,10,'2026-10-01',50,50,'posted',0,'receipt'),
    (103,10,'2026-10-01',30,30,'posted',0,'expense'),
    (201,20,'2026-10-01',900,900,'posted',0,'payment'),
    (202,20,'2026-10-01',800,800,'posted',0,'receipt'),
    (203,20,'2026-10-01',700,700,'posted',0,'expense'),
])

# Station isolation.
station = 10
assert DB.execute("SELECT COALESCE(SUM(amount),0) FROM payments WHERE station_id=? AND status='completed' AND is_deleted=0", (station,)).fetchone()[0] == 100
assert DB.execute("SELECT COALESCE(SUM(amount),0) FROM receipts WHERE station_id=? AND status='active' AND is_deleted=0", (station,)).fetchone()[0] == 50
assert DB.execute("SELECT COALESCE(SUM(total_amount),0) FROM expenses WHERE station_id=? AND status='paid' AND is_deleted=0", (station,)).fetchone()[0] == 30

# Accounting reconciliation by source reference.
for table, column, status, ref in [('payments','amount','completed','payment'),('receipts','amount','active','receipt'),('expenses','total_amount','paid','expense')]:
    source = DB.execute(f"SELECT COALESCE(SUM({column}),0) FROM {table} WHERE station_id=? AND status=? AND is_deleted=0", (station,status)).fetchone()[0]
    journal = DB.execute("SELECT COALESCE(SUM(total_debit),0) FROM journal_entries WHERE station_id=? AND reference_type=? AND status='posted' AND is_deleted=0", (station,ref)).fetchone()[0]
    assert abs(source-journal) < 1e-9, (table, source, journal)

# Posted journal must remain balanced and station scoped.
unbalanced = DB.execute("SELECT COUNT(*) FROM journal_entries WHERE station_id=? AND status='posted' AND is_deleted=0 AND ABS(total_debit-total_credit)>0.000001", (station,)).fetchone()[0]
assert unbalanced == 0

# Date boundary: only the selected day is included.
DB.execute("UPDATE journal_entries SET entry_date='2026-10-02' WHERE id=103")
rows = DB.execute("SELECT COALESCE(SUM(total_debit),0) FROM journal_entries WHERE station_id=? AND status='posted' AND is_deleted=0 AND date(entry_date) BETWEEN date(?) AND date(?)", (station,'2026-10-01','2026-10-01')).fetchone()[0]
assert rows == 150, rows

print('PASS: finance/accounting station, date, source-to-journal reconciliation, and balance invariants')
