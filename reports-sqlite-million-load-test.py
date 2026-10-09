"""SQLite report-query load analysis at million-row scale.

Synthetic in-memory data only; no application database is modified. The schema and
predicates mirror the indexed business_day and journal-entry report paths.
"""
from __future__ import annotations

import json
import resource
import sqlite3
import statistics
import time
from typing import Callable

SALES_ROWS = 1_000_000
JOURNAL_ROWS = 250_000
ITERATIONS = 10
STATION_ID = 7
FROM_DATE = "2026-01-01"
TO_DATE = "2026-12-31"
P95_LIMIT_MS = 1500


def rss_mb() -> float:
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0


def date_for(i: int) -> str:
    day = (i % 365) + 1
    month = ((day - 1) // 30) + 1
    return f"2026-{month:02d}-{((day - 1) % 30) + 1:02d}"


def setup() -> tuple[sqlite3.Connection, float]:
    started = time.perf_counter()
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA journal_mode=MEMORY")
    db.execute("PRAGMA synchronous=OFF")
    db.execute("PRAGMA temp_store=MEMORY")
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
        CREATE INDEX idx_sales_station
            ON sales_transactions(station_id);
        CREATE INDEX idx_journal_station_date_status
            ON journal_entries(station_id, entry_date, status, is_deleted);
        """
    )
    sales = []
    for i in range(1, SALES_ROWS + 1):
        amount = float((i % 97) + 1)
        sales.append((i, STATION_ID if i % 10 else 8, date_for(i), 0, "completed", "cash" if i % 2 else "card", amount, amount, 0.0))
        if len(sales) == 10_000:
            db.executemany("INSERT INTO sales_transactions VALUES (?,?,?,?,?,?,?,?,?)", sales)
            sales.clear()
    if sales:
        db.executemany("INSERT INTO sales_transactions VALUES (?,?,?,?,?,?,?,?,?)", sales)

    journals = []
    for i in range(1, JOURNAL_ROWS + 1):
        amount = float((i % 211) + 1)
        status = "reversed" if i % 37 == 0 else "posted"
        journals.append((i, STATION_ID if i % 10 else 8, date_for(i), status, 0, amount, amount, i - 1 if status == "reversed" else None))
        if len(journals) == 10_000:
            db.executemany("INSERT INTO journal_entries VALUES (?,?,?,?,?,?,?,?)", journals)
            journals.clear()
    if journals:
        db.executemany("INSERT INTO journal_entries VALUES (?,?,?,?,?,?,?,?)", journals)
    db.execute("ANALYZE")
    db.commit()
    return db, round(time.perf_counter() - started, 3)


def plan(db: sqlite3.Connection, sql: str, args: tuple[object, ...]) -> str:
    return " | ".join(str(row[-1]) for row in db.execute("EXPLAIN QUERY PLAN " + sql, args))


def benchmark(db: sqlite3.Connection, name: str, sql: str, args: tuple[object, ...], check: Callable[[tuple], None]) -> dict:
    check(db.execute(sql, args).fetchone())
    samples = []
    for _ in range(ITERATIONS):
        started = time.perf_counter()
        row = db.execute(sql, args).fetchone()
        samples.append((time.perf_counter() - started) * 1000)
        check(row)
    ordered = sorted(samples)
    return {
        "query": name,
        "median_ms": round(statistics.median(samples), 3),
        "p95_ms": round(ordered[max(0, int(ITERATIONS * 0.95) - 1)], 3),
        "max_ms": round(max(samples), 3),
        "plan": plan(db, sql, args),
    }


def assert_count(row: tuple) -> None:
    assert row is not None and row[0] > 0, row


def assert_month(row: tuple) -> None:
    assert row is not None and isinstance(row[0], str) and row[1] > 0, row


def run_suite(db: sqlite3.Connection, queries: dict, label: str) -> list[dict]:
    results = []
    for name, (sql, args, check) in queries.items():
        result = benchmark(db, name, sql, args, check)
        result["phase"] = label
        results.append(result)
        if label == "AFTER":
            assert "SCAN" not in result["plan"].upper() or "USING INDEX" in result["plan"].upper(), result
        print(f"{label} {name}: median={result['median_ms']}ms p95={result['p95_ms']}ms max={result['max_ms']}ms")
        print(f"  plan: {result['plan']}")
    return results


def main() -> None:
    before = rss_mb()
    db, setup_seconds = setup()
    after_setup = rss_mb()
    queries = {
        "sales_business_day_summary": ("""
            SELECT COUNT(*), COALESCE(SUM(net_amount),0), COALESCE(SUM(paid_amount),0)
            FROM sales_transactions
            WHERE station_id=? AND is_deleted=0 AND business_day BETWEEN ? AND ?
        """, (STATION_ID, FROM_DATE, TO_DATE), assert_count),
        "sales_business_day_monthly": ("""
            SELECT substr(business_day,1,7), COUNT(*), COALESCE(SUM(net_amount),0)
            FROM sales_transactions
            WHERE station_id=? AND is_deleted=0 AND business_day BETWEEN ? AND ?
            GROUP BY substr(business_day,1,7) ORDER BY 1
        """, (STATION_ID, FROM_DATE, TO_DATE), assert_month),
        "sales_payment_breakdown": ("""
            SELECT payment_method, COUNT(*), COALESCE(SUM(net_amount),0)
            FROM sales_transactions
            WHERE station_id=? AND is_deleted=0 AND business_day BETWEEN ? AND ?
            GROUP BY payment_method ORDER BY payment_method
        """, (STATION_ID, FROM_DATE, TO_DATE), assert_month),
        "journal_reconciliation_summary": ("""
            SELECT COUNT(*), COALESCE(SUM(total_debit),0), COALESCE(SUM(total_credit),0),
                   SUM(CASE WHEN status='reversed' AND (reversed_entry_id IS NULL OR reversed_entry_id<=0) THEN 1 ELSE 0 END)
            FROM journal_entries
            WHERE station_id=? AND is_deleted=0 AND entry_date BETWEEN ? AND ?
        """, (STATION_ID, FROM_DATE, TO_DATE), assert_count),
    }
    before_results = run_suite(db, queries, "BEFORE")
    db.executescript(
        """
        CREATE INDEX IF NOT EXISTS idx_sales_station_business_day_deleted_payment
            ON sales_transactions(station_id, business_day, is_deleted, payment_method);
        CREATE INDEX IF NOT EXISTS idx_sales_station_payment_business_day_deleted
            ON sales_transactions(station_id, payment_method, business_day, is_deleted);
        """
    )
    db.execute("ANALYZE")
    after_results = run_suite(db, queries, "AFTER")
    for result in after_results:
        assert result["p95_ms"] < P95_LIMIT_MS, result
    before_by_name = {result["query"]: result for result in before_results}
    for result in after_results:
        result["delta_ms"] = round(result["p95_ms"] - before_by_name[result["query"]]["p95_ms"], 3)
        result["improvement_pct"] = round((1 - result["p95_ms"] / before_by_name[result["query"]]["p95_ms"]) * 100, 2)
    report = {
        "sales_rows": SALES_ROWS,
        "journal_rows": JOURNAL_ROWS,
        "iterations_per_query": ITERATIONS,
        "setup_seconds": setup_seconds,
        "rss_before_mb": round(before, 2),
        "rss_after_setup_mb": round(after_setup, 2),
        "rss_delta_mb": round(after_setup - before, 2),
        "baseline_queries": before_results,
        "optimized_queries": after_results,
        "composite_indexes": [
            "idx_sales_station_business_day_deleted_payment",
            "idx_sales_station_payment_business_day_deleted",
        ],
        "p95_limit_ms": P95_LIMIT_MS,
    }
    with open("reports-sqlite-million-load-results.json", "w", encoding="utf-8") as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
    print(f"SQLite million-row load PASS: {SALES_ROWS:,} sales + {JOURNAL_ROWS:,} journal rows")
    print(f"Setup={setup_seconds}s RSS delta={after_setup - before:.2f}MB")


if __name__ == "__main__":
    main()
