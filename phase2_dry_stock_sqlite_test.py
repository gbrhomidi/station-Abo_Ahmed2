#!/usr/bin/env python3
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parent
migration = (root / 'docs/phase2_dry_stock_migration.sql').read_text()
rollback = (root / 'docs/phase2_dry_stock_rollback.sql').read_text()

def main():
    db = sqlite3.connect(':memory:')
    db.executescript('''
      PRAGMA foreign_keys=ON;
      CREATE TABLE users(id INTEGER PRIMARY KEY);
      CREATE TABLE parties(id INTEGER PRIMARY KEY);
      CREATE TABLE warehouses(id INTEGER PRIMARY KEY, station_id INTEGER, is_active INTEGER DEFAULT 1);
      CREATE TABLE stations(id INTEGER PRIMARY KEY);
      CREATE TABLE products(id INTEGER PRIMARY KEY, station_id INTEGER, product_name TEXT, quantity REAL DEFAULT 0, purchase_price REAL DEFAULT 0, minimum_stock REAL DEFAULT 0, expiry_date TEXT, status TEXT DEFAULT 'active', is_deleted INTEGER DEFAULT 0);
      CREATE TABLE sales_transactions(id INTEGER PRIMARY KEY);
      CREATE TABLE sale_items(id INTEGER PRIMARY KEY, sale_id INTEGER, product_id INTEGER, quantity REAL);
    ''')
    db.executescript(migration)
    db.execute("INSERT INTO stations VALUES (1)")
    db.execute("INSERT INTO warehouses VALUES (10,1,1)")
    db.execute("INSERT INTO products(id,station_id,product_name,quantity,purchase_price) VALUES (7,1,'بطارية',10,20)")
    db.execute("INSERT INTO product_batches(product_id,lot_number,expiry_date,quantity,received_at) VALUES (7,'LATE','2027-12-01',5,'2026-01-01')")
    db.execute("INSERT INTO product_batches(product_id,lot_number,expiry_date,quantity,received_at) VALUES (7,'EARLY','2027-01-01',5,'2026-02-01')")
    chosen = db.execute("SELECT lot_number FROM product_batches WHERE product_id=7 AND quantity>=? ORDER BY expiry_date LIMIT 1", (3,)).fetchone()[0]
    assert chosen == 'EARLY', chosen
    db.execute("INSERT INTO sales_transactions(id) VALUES (99)")
    db.execute("INSERT INTO product_serials(product_id,serial_no,warranty_end_at) VALUES (7,'SN-001','2028-01-01')")
    serial_id = db.execute("SELECT serial_id FROM product_serials WHERE serial_no='SN-001'").fetchone()[0]
    db.execute("UPDATE product_serials SET status='SOLD',sale_id=99 WHERE serial_id=? AND status='AVAILABLE'", (serial_id,))
    assert db.execute("SELECT status FROM product_serials WHERE serial_id=?", (serial_id,)).fetchone()[0] == 'SOLD'
    db.executescript(rollback)
    assert db.execute("SELECT COUNT(*) FROM products").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM sale_items").fetchone()[0] == 0
    for name in ('product_batches', 'product_serials', 'stock_locations'):
        assert db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone() is None
    print('Phase 2 SQLite contract test: PASS')

if __name__ == '__main__':
    main()
