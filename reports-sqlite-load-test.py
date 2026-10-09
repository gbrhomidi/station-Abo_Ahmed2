"""SQLite load test for large operational report queries.

The test uses an in-memory SQLite database so it leaves no project data behind. It
models the indexed predicates used by DatabaseHelper: station_id + business_day,
accounting entry_date, journal status, and reversal linkage.
"""
from __future__ import annotations

import sqlite3
import statistics
import time
from typing import Callable

ROWS = 120_000
JOURNAL_ROWS = 30_000
ITERATIONS = 20
STATION_ID = 7
FROM_DATE = "2026-01-01"
TO_DATE = "2026-12-31"


def setup() -> sqlite3.Connection:
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA journal_mode=MEMORY")
    db.execute("PRAGMA synchronous=OFF")
    db.executescript(
        """
        CREATE TABLE sales_transactions (
            id INTEGER PRIMARY KEY,
            station_id INTEGER NOT NULL,
            business_day TEXT NOT NULL,
            is_deleted INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL,
            payment_method TEXT NOT NULL,
            net_amount REAL NOT NULL,
            paid_amount REAL NOT NULL,
            remaining_amount REAL NOT NULL
        );
        CREATE TABLE journal_entries (
            id INTEGER PRIMARY KEY,
            station_id INTEGER NOT NULL,
            entry_date TEXT NOT NULL,
            status TEXT NOT NULL,
            is_deleted INTEGER NOT NULL DEFAULT 0,
            total_debit REAL NOT NULL,
            total_credit REAL NOT NULL,
            reversed_entry_id INTEGER
        );
        CREATE INDEX idx_sales_station_business_day
            ON sales_transactions(station_id, business_day, is_deleted);
        CREATE INDEX idx_journal_station_date_status
            ON journal_entries(station_id, entry_date, status, is_deleted);
        """
    )
    sales = []
    for i in range(1, ROWS + 1):
        day = (i % 365) + 1
        month = ((day - 1) // 30) + 1
        date = f"2026-{month:02d}-{((day - 1) % 30) + 1:02d}"
        station = STATION_ID if i % 10 else 8
        amount = float((i % 97) + 1)
        sales.append((i, station, date, 0, "completed", "cash" if i % 2 else "card", amount, amount, 0.0))
    db.executemany("INSERT INTO sales_transactions VALUES (?,?,?,?,?,?,?,?,?)", sales)

    journals = []
    for i in range(1, JOURNAL_ROWS + 1):
        day = (i % 365) + 1
        month = ((day - 1) // 30) + 1
        date = f"2026-{month:02d}-{((day - 1) % 30) + 1:02d}"
        station = STATION_ID if i % 10 else 8
        amount = float((i % 211) + 1)
        status = "reversed" if i % 37 == 0 else "posted"
        journals.append((i, station, date, status, 0, amount, amount, i - 1 if status == "reversed" else None))
    db.executemany("INSERT INTO journal_entries VALUES (?,?,?,?,?,?,?,?)", journals)
    db.execute("ANALYZE")
    return db


def explain_uses_index(db: sqlite3.Connection, sql: str, args: tuple[object, ...]) -> None:
    plan = " ".join(str(row[-1]).upper() for row in db.execute("EXPLAIN QUERY PLAN " + sql, args))
    assert "USING INDEX IDX_SALES_STATION_BUSINESS_DAY" in plan, plan


def benchmark(db: sqlite3.Connection, name: str, sql: str, args: tuple[object, ...], check: Callable[[tuple], None]) -> dict:
    check(db.execute(sql, args).fetchone())
    samples = []
    for _ in range(ITERATIONS):
        started = time.perf_counter()
        row = db.execute(sql, args).fetchone()
        elapsed_ms = (time.perf_counter() - started) * 1000
        check(row)
        samples.append(elapsed_ms)
    return {
        "query": name,
        "iterations": ITERATIONS,
        "rows": ROWS if "sales" in name else JOURNAL_ROWS,
        "median_ms": round(statistics.median(samples), 3),
        "p95_ms": round(sorted(samples)[int(ITERATIONS * 0.95) - 1], 3),
        "max_ms": round(max(samples), 3),
    }


def main() -> None:
    db = setup()
    sales_sql = """
        SELECT COUNT(*), COALESCE(SUM(net_amount), 0),
               COALESCE(SUM(paid_amount), 0), COALESCE(SUM(remaining_amount), 0)
        FROM sales_transactions
        WHERE station_id=? AND is_deleted=0 AND business_day BETWEEN ? AND ?
    """
    monthly_sql = """
        SELECT substr(business_day, 1, 7), COUNT(*), COALESCE(SUM(net_amount), 0)
        FROM sales_transactions
        WHERE station_id=? AND is_deleted=0 AND business_day BETWEEN ? AND ?
        GROUP BY substr(business_day, 1, 7) ORDER BY 1
    """
    journal_sql = """
        SELECT COUNT(*), COALESCE(SUM(total_debit), 0), COALESCE(SUM(total_credit), 0),
               SUM(CASE WHEN status='reversed' AND (reversed_entry_id IS NULL OR reversed_entry_id<=0) THEN 1 ELSE 0 END)
        FROM journal_entries
        WHERE station_id=? AND is_deleted=0 AND entry_date BETWEEN ? AND ?
    """
    explain_uses_index(db, sales_sql, (STATION_ID, FROM_DATE, TO_DATE))
    results = [
        benchmark(db, "sales_business_day_summary", sales_sql, (STATION_ID, FROM_DATE, TO_DATE), lambda row: assert_row(row, 0)),
        benchmark(db, "sales_business_day_monthly", monthly_sql, (STATION_ID, FROM_DATE, TO_DATE), lambda row: assert_month_row(row)),
        benchmark(db, "journal_reconciliation_summary", journal_sql, (STATION_ID, FROM_DATE, TO_DATE), lambda row: assert_row(row, 0)),
    ]
    for result in results:
        # A report query over 120k/30k rows should remain comfortably interactive.
        assert result["p95_ms"] < 250, result
        print(f"PASS {result['query']}: p95={result['p95_ms']}ms median={result['median_ms']}ms max={result['max_ms']}ms")
    print(f"SQLite load test PASS: {ROWS} sales + {JOURNAL_ROWS} journal rows, {ITERATIONS} iterations/query")


def assert_row(row: tuple, minimum: int) -> None:
    assert row is not None and row[0] >= minimum, row


def assert_month_row(row: tuple) -> None:
    assert row is not None and isinstance(row[0], str) and row[1] > 0, row


if __name__ == "__main__":
    main()
