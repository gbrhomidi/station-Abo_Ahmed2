#!/usr/bin/env python3
import sqlite3


def setup_db():
    db = sqlite3.connect(":memory:")
    db.executescript(
        """
        CREATE TABLE party_types (
            id INTEGER PRIMARY KEY,
            uuid TEXT NOT NULL,
            type_code TEXT NOT NULL,
            type_name TEXT NOT NULL,
            type_name_ar TEXT,
            default_discount REAL DEFAULT 0,
            default_credit_limit REAL DEFAULT 0,
            payment_terms_days INTEGER DEFAULT 0,
            is_active INTEGER DEFAULT 1,
            is_deleted INTEGER DEFAULT 0
        );
        CREATE TABLE parties (
            id INTEGER PRIMARY KEY,
            uuid TEXT NOT NULL,
            party_code TEXT NOT NULL,
            party_type_id INTEGER NOT NULL,
            station_id INTEGER NOT NULL,
            commercial_name TEXT,
            is_active INTEGER DEFAULT 1,
            is_deleted INTEGER DEFAULT 0,
            credit_limit REAL DEFAULT 0,
            current_balance REAL DEFAULT 0,
            total_purchases REAL DEFAULT 0,
            total_payments REAL DEFAULT 0
        );
        CREATE TABLE sales_transactions (
            id INTEGER PRIMARY KEY,
            station_id INTEGER NOT NULL,
            customer_party_id INTEGER,
            net_amount REAL DEFAULT 0,
            is_deleted INTEGER DEFAULT 0,
            created_at TEXT
        );
        INSERT INTO party_types VALUES
            (1, 'PT-1', 'INDIVIDUAL', 'Individual', 'فرد', 0, 0, 0, 1, 0),
            (2, 'PT-2', 'COMPANY', 'Company', 'شركة', 5, 500000, 30, 1, 0);
        INSERT INTO parties VALUES
            (101, 'P-101', 'A-1', 1, 10, 'عميل المحطة A', 1, 0, 1000, 100, 500, 400),
            (102, 'P-102', 'B-1', 1, 20, 'عميل المحطة B', 1, 0, 2000, 200, 700, 600),
            (103, 'P-103', 'A-2', 2, 10, 'شركة المحطة A', 0, 0, 3000, 300, 800, 700),
            (104, 'P-104', 'DELETED', 1, 10, 'محذوف', 1, 1, 0, 0, 0, 0);
        INSERT INTO sales_transactions VALUES
            (1001, 10, 101, 250, 0, '2026-08-01'),
            (1002, 20, 102, 900, 0, '2026-08-02'),
            (1003, 20, 101, 999, 0, '2026-08-03'),
            (1004, 10, 101, 777, 1, '2026-08-04');
        """
    )
    return db


def report(db, kind, station):
    party_scope = " AND p.station_id = ?"
    sales_scope = " AND s.station_id = ?"
    if kind == "parties_by_type":
        sql = f"""
            SELECT pt.id AS type_id, COUNT(p.id) AS total_parties,
                   SUM(CASE WHEN p.is_active=1 THEN 1 ELSE 0 END) AS active_parties,
                   SUM(CASE WHEN p.is_active=0 THEN 1 ELSE 0 END) AS inactive_parties,
                   COALESCE(SUM(p.total_purchases),0) AS total_purchases,
                   COALESCE(SUM(p.total_payments),0) AS total_payments
            FROM party_types pt LEFT JOIN parties p
              ON p.party_type_id=pt.id AND p.is_deleted=0{party_scope}
            WHERE pt.is_deleted=0 GROUP BY pt.id ORDER BY pt.id
        """
        args = [station]
    elif kind == "credit_analysis":
        sql = f"""
            SELECT pt.id AS type_id, COUNT(p.id) AS party_count,
                   COALESCE(AVG(p.credit_limit),0) AS avg_credit,
                   COALESCE(SUM(p.credit_limit),0) AS total_credit,
                   COALESCE(SUM(p.current_balance),0) AS total_balance
            FROM party_types pt LEFT JOIN parties p
              ON p.party_type_id=pt.id AND p.is_deleted=0{party_scope}
            WHERE pt.is_deleted=0 GROUP BY pt.id ORDER BY pt.id
        """
        args = [station]
    else:
        sql = f"""
            SELECT pt.id AS type_id, COUNT(DISTINCT s.id) AS invoice_count,
                   COALESCE(SUM(s.net_amount),0) AS total_sales
            FROM party_types pt LEFT JOIN parties p
              ON p.party_type_id=pt.id AND p.is_deleted=0{party_scope}
            LEFT JOIN sales_transactions s
              ON s.customer_party_id=p.id AND s.is_deleted=0{sales_scope}
            WHERE pt.is_deleted=0 GROUP BY pt.id ORDER BY pt.id
        """
        args = [station, station]
    return db.execute(sql, args).fetchall()


def main():
    db = setup_db()
    a = report(db, "parties_by_type", 10)
    b = report(db, "parties_by_type", 20)
    assert a[0][1] == 1 and a[1][1] == 1, a
    assert b[0][1] == 1 and b[1][1] == 0, b

    credit_a = report(db, "credit_analysis", 10)
    assert credit_a[0][1:] == (1, 1000.0, 1000.0, 100.0), credit_a

    activity_a = report(db, "activity", 10)
    activity_b = report(db, "activity", 20)
    assert activity_a[0][1:] == (1, 250.0), activity_a
    assert activity_b[0][1:] == (1, 900.0), activity_b
    assert all(row[1] == 0 and row[2] == 0 for row in activity_a[1:]), activity_a

    linked = db.execute("SELECT COUNT(*) FROM parties WHERE party_type_id=? AND is_deleted=0", (1,)).fetchone()[0]
    assert linked == 2, linked
    assert db.execute("SELECT COUNT(*) FROM sales_transactions WHERE is_deleted=0 AND station_id=?", (10,)).fetchone()[0] == 1
    print("MODULE-003 party_types SQLite scope tests: PASS (station A/B, counts, credit, activity, soft-delete exclusion, linked-type guard)")


if __name__ == "__main__":
    main()
