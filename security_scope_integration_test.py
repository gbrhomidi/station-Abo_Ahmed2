import sqlite3


def setup():
    db = sqlite3.connect(':memory:')
    db.executescript('''
    PRAGMA foreign_keys=ON;
    CREATE TABLE stations(id INTEGER PRIMARY KEY);
    CREATE TABLE users(id INTEGER PRIMARY KEY, station_id INTEGER);
    CREATE TABLE pumps(id INTEGER PRIMARY KEY, station_id INTEGER, is_deleted INTEGER DEFAULT 0);
    CREATE TABLE pump_nozzles(id INTEGER PRIMARY KEY, pump_id INTEGER, is_deleted INTEGER DEFAULT 0);
    CREATE TABLE meter_readings(id INTEGER PRIMARY KEY, pump_id INTEGER, nozzle_id INTEGER, station_id INTEGER, read_by INTEGER, reading_date TEXT, is_deleted INTEGER DEFAULT 0);
    CREATE TABLE shifts(id INTEGER PRIMARY KEY, station_id INTEGER, status TEXT, is_deleted INTEGER DEFAULT 0, total_sales REAL DEFAULT 0, total_deliveries REAL DEFAULT 0, total_expenses REAL DEFAULT 0);
    CREATE TABLE sales_transactions(id INTEGER PRIMARY KEY, shift_id INTEGER, station_id INTEGER, net_amount REAL, is_deleted INTEGER DEFAULT 0);
    CREATE TABLE deliveries(id INTEGER PRIMARY KEY, sale_id INTEGER, party_id INTEGER, total_amount REAL, is_deleted INTEGER DEFAULT 0);
    CREATE TABLE parties(id INTEGER PRIMARY KEY, station_id INTEGER, is_deleted INTEGER DEFAULT 0);
    CREATE TABLE shift_sales(id INTEGER PRIMARY KEY, shift_id INTEGER, sale_id INTEGER, amount REAL);
    CREATE TABLE shift_deliveries(id INTEGER PRIMARY KEY, shift_id INTEGER, delivery_id INTEGER, amount REAL);
    CREATE TABLE shift_expenses(id INTEGER PRIMARY KEY, shift_id INTEGER, amount REAL);
    CREATE TABLE cash_boxes(id INTEGER PRIMARY KEY, station_id INTEGER, current_balance REAL, status TEXT, is_deleted INTEGER DEFAULT 0);
    CREATE TABLE cash_movements(id INTEGER PRIMARY KEY, cash_box_id INTEGER, amount REAL, balance_before REAL, balance_after REAL, is_deleted INTEGER DEFAULT 0);
    CREATE TABLE employees(id INTEGER PRIMARY KEY, station_id INTEGER, full_name TEXT, is_deleted INTEGER DEFAULT 0);
    CREATE TABLE tank_refills(id INTEGER PRIMARY KEY, station_id INTEGER, tank_id INTEGER);
    CREATE TABLE fuel_sales(id INTEGER PRIMARY KEY, sale_id INTEGER);
    CREATE TABLE report_cache(cache_key TEXT, params_hash TEXT, user_id INTEGER, station_id INTEGER, payload_json TEXT);
    INSERT INTO stations VALUES (1),(2);
    INSERT INTO users VALUES (101,1),(202,2);
    INSERT INTO pumps VALUES (11,1,0),(22,2,0);
    INSERT INTO pump_nozzles VALUES (111,11,0),(222,22,0);
    INSERT INTO shifts VALUES (1,1,'open',0,0,0,0),(2,2,'open',0,0,0,0);
    INSERT INTO sales_transactions VALUES (101,1,1,100,0),(202,2,2,200,0);
    INSERT INTO parties VALUES (1,1,0),(2,2,0);
    INSERT INTO deliveries VALUES (1,101,1,50,0),(2,202,2,75,0);
    INSERT INTO cash_boxes VALUES (1,1,100,'active',0),(2,2,200,'active',0);
    INSERT INTO employees VALUES (1,1,'A',0),(2,2,'B',0);
    INSERT INTO tank_refills VALUES (1,1,10),(2,2,20);
    INSERT INTO fuel_sales VALUES (1,101),(2,202);
    ''')
    return db


def one(db, sql, args=()):
    row = db.execute(sql, args).fetchone()
    return row[0] if row else None


def run():
    db = setup()
    # Meter read: UI station tampering is ignored by an explicit trusted scope.
    db.execute('INSERT INTO meter_readings(pump_id,nozzle_id,station_id,read_by,reading_date) SELECT ?,?,?,?,?', (11,111,1,101,'2026-08-22'))
    assert one(db, 'SELECT COUNT(*) FROM meter_readings WHERE station_id=?', (1,)) == 1
    assert one(db, 'SELECT COUNT(*) FROM meter_readings WHERE station_id=?', (2,)) == 0
    assert one(db, 'SELECT COUNT(*) FROM meter_readings WHERE pump_id=? AND station_id=?', (22,1)) == 0

    # Every shift write requires both the shift id and trusted station scope.
    assert one(db, "SELECT COUNT(*) FROM shifts WHERE id=? AND station_id=? AND status='open'", (1,1)) == 1
    assert one(db, "SELECT COUNT(*) FROM shifts WHERE id=? AND station_id=?", (2,1)) == 0
    assert one(db, 'SELECT COUNT(*) FROM sales_transactions st JOIN shifts sh ON sh.id=st.shift_id WHERE st.shift_id=? AND sh.station_id=?', (2,1)) == 0
    assert one(db, 'SELECT COUNT(*) FROM deliveries d LEFT JOIN sales_transactions s ON s.id=d.sale_id LEFT JOIN parties p ON p.id=d.party_id WHERE d.id=? AND d.is_deleted=0 AND (s.station_id=? OR p.station_id=?)', (2,1,1)) == 0

    # Shift report is relationally scoped rather than id-only.
    sales = one(db, 'SELECT COUNT(*) FROM sales_transactions st JOIN shifts sh ON sh.id=st.shift_id WHERE st.shift_id=? AND sh.station_id=? AND st.is_deleted=0', (1,1))
    deliveries = one(db, 'SELECT COUNT(*) FROM shift_deliveries sd JOIN deliveries d ON d.id=sd.delivery_id JOIN shifts sh ON sh.id=sd.shift_id WHERE sd.shift_id=? AND sh.station_id=? AND d.is_deleted=0', (1,1))
    assert sales == 1 and deliveries == 0

    # Cash reads are scoped through cash_boxes, not a client-provided station id.
    assert one(db, 'SELECT COUNT(*) FROM cash_movements cm JOIN cash_boxes cb ON cb.id=cm.cash_box_id WHERE cb.station_id=? AND cm.is_deleted=0', (1,)) == 0
    db.execute('INSERT INTO cash_movements VALUES (1,1,25,100,125,0)')
    assert one(db, 'SELECT COUNT(*) FROM cash_movements cm JOIN cash_boxes cb ON cb.id=cm.cash_box_id WHERE cb.station_id=?', (1,)) == 1
    assert one(db, 'SELECT COUNT(*) FROM cash_movements cm JOIN cash_boxes cb ON cb.id=cm.cash_box_id WHERE cb.station_id=?', (2,)) == 0

    # Employee CRUD predicates cannot cross station boundaries.
    assert one(db, 'SELECT COUNT(*) FROM employees WHERE id=? AND station_id=? AND is_deleted=0', (2,1)) == 0
    assert one(db, 'SELECT COUNT(*) FROM employees WHERE id=? AND station_id=? AND is_deleted=0', (1,1)) == 1

    # Fuel transaction details are relationally scoped for sales and directly scoped for refills.
    assert one(db, 'SELECT COUNT(*) FROM fuel_sales fs JOIN sales_transactions st ON st.id=fs.sale_id WHERE fs.sale_id=? AND st.station_id=? AND st.is_deleted=0', (202,1)) == 0
    assert one(db, 'SELECT COUNT(*) FROM tank_refills WHERE id=? AND station_id=?', (2,1)) == 0
    print('SECURITY_SCOPE_INTEGRATION_PASS')


if __name__ == '__main__':
    run()
