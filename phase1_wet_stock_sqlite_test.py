#!/usr/bin/env python3
"""Offline contract test for the additive Phase 1 wet-stock migration."""
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MIGRATION = (ROOT / "docs/phase1_wet_stock_migration.sql").read_text(encoding="utf-8")
ROLLBACK = (ROOT / "docs/phase1_wet_stock_rollback.sql").read_text(encoding="utf-8")

def main():
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    db.executescript("""
        CREATE TABLE users(id INTEGER PRIMARY KEY);
        CREATE TABLE stations(id INTEGER PRIMARY KEY);
        CREATE TABLE fuel_types(id INTEGER PRIMARY KEY);
        CREATE TABLE tanks(id INTEGER PRIMARY KEY, station_id INTEGER, tank_name TEXT,
                           tank_name_ar TEXT, capacity_liters REAL, current_quantity REAL,
                           status TEXT, is_deleted INTEGER DEFAULT 0, deleted_at TEXT);
        CREATE TABLE shifts(id INTEGER PRIMARY KEY, station_id INTEGER, start_time TEXT,
                            end_time TEXT, status TEXT);
    """)
    db.execute("INSERT INTO users VALUES (1)")
    db.execute("INSERT INTO stations VALUES (1)")
    db.execute("INSERT INTO tanks VALUES (3,1,'T3','الخزان 3',10000,0,'active',0,NULL)")
    db.execute("INSERT INTO shifts VALUES (88,1,'2026-11-17 08:00:00',NULL,'open')")
    db.executescript(MIGRATION)
    db.execute("INSERT INTO tank_readings(tank_id,reading_at,current_liters,source,recorded_by) VALUES (3,'2026-11-17 08:00:00',1000,'manual',1)")
    # expected = opening + delivery - sales + adjustment = 1000 + 500 - 200 + 0 = 1300
    expected = 1000.0 + 500.0 - 200.0
    actual = 1295.0
    variance = actual - expected
    pct = variance / expected * 100.0
    db.execute("""INSERT INTO shift_reconciliations
        (shift_id,tank_id,opening_liters,deliveries_liters,sales_liters,expected_liters,
         actual_liters,variance_liters,variance_pct,cause,notes)
        VALUES (88,3,1000,500,200,?,?,?,?,?,?)""",
        (expected, actual, variance, pct, "METER_ERROR", "اختبار فرق قراءة"))
    row = db.execute("SELECT expected_liters, variance_liters, variance_pct FROM shift_reconciliations").fetchone()
    assert abs(row[0] - 1300.0) < 0.01, row
    assert abs(row[1] + 5.0) < 0.01, row
    assert abs(row[2] - (-5.0 / 1300.0 * 100.0)) < 0.000001, row
    db.execute("INSERT INTO tank_dip_readings(tank_id,dip_cm,dip_liters,book_liters,variance_liters,variance_percent,reason_code,created_at) VALUES (3,100,1295,1300,-5,-0.384615,'METER_ERROR','2026-11-17 23:00:00')")
    assert db.execute("SELECT COUNT(*) FROM tank_dip_readings").fetchone()[0] == 1
    db.executescript(ROLLBACK)
    for table in ("tank_readings", "tank_dip_readings", "shift_reconciliations", "inventory_reason_codes"):
        assert db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone() is None, table
    assert db.execute("SELECT COUNT(*) FROM tanks").fetchone()[0] == 1
    print("Phase 1 SQLite contract test: PASS")

if __name__ == "__main__":
    main()
