#!/usr/bin/env python3
"""Regression checks for the station/dashboard SQLite access paths.

The production schema installs the same index definitions in DatabaseHelper.
This deliberately uses representative tables so the test stays deterministic and
can run in CI without an Android emulator or a copied production database.
"""
import sqlite3


def details(connection, sql, args=()):
    return [row[3] for row in connection.execute("EXPLAIN QUERY PLAN " + sql, args)]


def require_index(plan, index_name):
    joined = "\n".join(plan)
    if index_name not in joined:
        raise AssertionError(f"Expected {index_name} in query plan:\n{joined}")


def main():
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA automatic_index=OFF")
    db.executescript(
        """
        CREATE TABLE sales_transactions (
            id INTEGER PRIMARY KEY, station_id INTEGER, business_day TEXT,
            is_deleted INTEGER, due_date TEXT, customer_party_id INTEGER,
            payment_method TEXT, remaining_amount REAL
        );
        CREATE TABLE parties (
            id INTEGER PRIMARY KEY, station_id INTEGER, is_deleted INTEGER,
            is_active INTEGER, party_type_id INTEGER
        );
        CREATE TABLE tasks (
            id INTEGER PRIMARY KEY, station_id INTEGER, is_deleted INTEGER,
            is_archived INTEGER, is_resolved INTEGER
        );
        CREATE INDEX idx_sales_business_day
            ON sales_transactions(station_id, business_day, is_deleted);
        CREATE INDEX idx_sales_debt_station_deleted_due
            ON sales_transactions(station_id, is_deleted, due_date, id);
        CREATE INDEX idx_sales_dashboard_customer
            ON parties(station_id, is_deleted, is_active, id);
        CREATE INDEX idx_dashboard_tasks_scope_state
            ON tasks(station_id, is_deleted, is_archived, is_resolved);
        """
    )

    require_index(
        details(
            db,
            "SELECT COUNT(*) FROM sales_transactions "
            "WHERE station_id=? AND business_day=? AND is_deleted=0",
            (1, "2026-11-06"),
        ),
        "idx_sales_business_day",
    )
    require_index(
        details(
            db,
            "SELECT id FROM sales_transactions "
            "WHERE station_id=? AND is_deleted=0 AND due_date>=? "
            "AND due_date<? ORDER BY due_date, id",
            (1, "2026-11-06", "2026-11-14"),
        ),
        "idx_sales_debt_station_deleted_due",
    )
    require_index(
        details(
            db,
            "SELECT COUNT(*) FROM parties "
            "WHERE station_id=? AND is_deleted=0 AND is_active=1",
            (1,),
        ),
        "idx_sales_dashboard_customer",
    )
    require_index(
        details(
            db,
            "SELECT COUNT(*) FROM tasks "
            "WHERE station_id=? AND is_deleted=0 AND is_archived=0 AND is_resolved=0",
            (1,),
        ),
        "idx_dashboard_tasks_scope_state",
    )
    print("EXPLAIN QUERY PLAN regression: PASS")


if __name__ == "__main__":
    main()
