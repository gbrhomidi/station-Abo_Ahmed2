#!/usr/bin/env python3
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parent
migration = (root / 'docs/phase3_phase4_migration.sql').read_text()
rollback = (root / 'docs/phase3_phase4_rollback.sql').read_text()

def main():
    db = sqlite3.connect(':memory:')
    db.executescript('''
      CREATE TABLE stations(id INTEGER PRIMARY KEY);
      CREATE TABLE products(id INTEGER PRIMARY KEY, station_id INTEGER, quantity REAL, reorder_point REAL, minimum_stock REAL, purchase_price REAL, is_deleted INTEGER DEFAULT 0);
      CREATE TABLE tanks(id INTEGER PRIMARY KEY, station_id INTEGER, current_quantity REAL, capacity_liters REAL, is_deleted INTEGER DEFAULT 0);
      CREATE TABLE shifts(id INTEGER PRIMARY KEY, station_id INTEGER, status TEXT);
      CREATE TABLE fuel_sales(id INTEGER PRIMARY KEY, shift_id INTEGER, quantity REAL, total_amount REAL, is_deleted INTEGER DEFAULT 0);
      CREATE TABLE sales_transactions(id INTEGER PRIMARY KEY, station_id INTEGER, net_amount REAL, status TEXT, created_at TEXT);
      CREATE TABLE tank_readings(reading_id INTEGER PRIMARY KEY, tank_id INTEGER, reading_at TEXT, water_level_mm REAL);
    ''')
    db.executescript(migration)
    db.execute('INSERT INTO stations VALUES (1)')
    db.execute('INSERT INTO products VALUES (7,1,2,10,10,20,0)')
    db.execute('INSERT INTO purchase_orders(po_id,po_number,station_id) VALUES (1,"PO-1",1)')
    db.execute('INSERT INTO purchase_order_lines(po_id,item_type,item_id,quantity) VALUES (1,"PRODUCT",7,8)')
    db.execute('INSERT INTO events_outbox(event_type,payload) VALUES ("pos_sale_completed","{}")')
    assert db.execute("SELECT status FROM events_outbox WHERE event_id=1").fetchone()[0] == 'PENDING'
    assert db.execute('SELECT quantity FROM purchase_order_lines WHERE po_id=1').fetchone()[0] == 8
    db.executescript(rollback)
    for name in ('purchase_orders', 'purchase_order_lines', 'events_outbox', 'inventory_forecasts', 'inventory_anomalies', 'inventory_health_snapshots'):
        assert db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone() is None
    assert db.execute('SELECT COUNT(*) FROM products').fetchone()[0] == 1
    print('Phase 3/4 SQLite contract test: PASS')

if __name__ == '__main__':
    main()
