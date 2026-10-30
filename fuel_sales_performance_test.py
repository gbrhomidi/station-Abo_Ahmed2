"""Indexed-query benchmark for fuel sales and station-scoped customer lookups.

The fixture models the production joins and station predicates, builds a larger,
deterministic two-station data set, prints SQLite EXPLAIN QUERY PLAN details, and
reports measured median / nearest-rank P95 latency.

Optional CI tuning:
  PERF_PARTY_COUNT=20000 PERF_SALE_COUNT=100000 PERF_ITERATIONS=40
"""
import math
import os
import sqlite3
import statistics
import time

PARTY_COUNT = max(1_000, int(os.environ.get("PERF_PARTY_COUNT", "20000")))
SALE_COUNT = max(5_000, int(os.environ.get("PERF_SALE_COUNT", "100000")))
ITERATIONS = max(20, int(os.environ.get("PERF_ITERATIONS", "40")))
WARMUP_ITERATIONS = max(1, int(os.environ.get("PERF_WARMUPS", "5")))
MAX_MEDIAN_MS = 250.0
MAX_P95_MS = 500.0
CUSTOMER_TYPE_CODES = ("INDIVIDUAL", "COMPANY", "GOVERNMENT", "TRANSPORT", "CONTRACTOR")


def setup_db(party_count=PARTY_COUNT, sale_count=SALE_COUNT):
    db = sqlite3.connect(":memory:")
    db.executescript("""
        PRAGMA foreign_keys = ON;
        PRAGMA temp_store = MEMORY;
        PRAGMA journal_mode = MEMORY;

        CREATE TABLE party_types (
            id INTEGER PRIMARY KEY,
            type_code TEXT NOT NULL,
            is_deleted INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE parties (
            id INTEGER PRIMARY KEY,
            station_id INTEGER NOT NULL,
            party_type_id INTEGER NOT NULL,
            commercial_name TEXT,
            commercial_name_ar TEXT,
            is_deleted INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE sales_transactions (
            id INTEGER PRIMARY KEY,
            station_id INTEGER NOT NULL,
            sale_code TEXT,
            invoice_number TEXT,
            is_deleted INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE fuel_sales (
            id INTEGER PRIMARY KEY,
            sale_id INTEGER NOT NULL,
            shift_id INTEGER,
            pump_id INTEGER,
            fuel_type_id INTEGER NOT NULL,
            quantity REAL NOT NULL,
            price_per_liter REAL NOT NULL,
            total_amount REAL NOT NULL,
            payment_method TEXT NOT NULL,
            sale_date TEXT NOT NULL,
            is_deleted INTEGER NOT NULL DEFAULT 0
        );
    """)

    party_types = [
        (index, code, 0)
        for index, code in enumerate(CUSTOMER_TYPE_CODES, start=1)
    ]
    db.executemany("INSERT INTO party_types VALUES (?,?,?)", party_types)

    station_one_parties = int(party_count * 0.75)
    parties = []
    for party_id in range(1, party_count + 1):
        station_id = 1 if party_id <= station_one_parties else 2
        party_type_id = (party_id % len(CUSTOMER_TYPE_CODES)) + 1
        parties.append((
            party_id,
            station_id,
            party_type_id,
            f"Customer {party_id:06d}",
            f"عميل {party_id:06d}",
            0,
        ))
    db.executemany(
        "INSERT INTO parties VALUES (?,?,?,?,?,?)",
        parties,
    )

    station_one_sales = int(sale_count * 0.75)
    sales = []
    fuel_sales = []
    for sale_id in range(1, sale_count + 1):
        station_id = 1 if sale_id <= station_one_sales else 2
        payment_method = "cash" if sale_id % 3 else "credit"
        sales.append((
            sale_id,
            station_id,
            f"SALE-{sale_id:08d}",
            f"INV-{sale_id:08d}",
            0,
        ))
        fuel_sales.append((
            sale_id,
            sale_id,
            1,
            1,
            1,
            10.0,
            1.5,
            15.0,
            payment_method,
            "2026-10-30",
            0,
        ))

    db.executemany(
        "INSERT INTO sales_transactions VALUES (?,?,?,?,?)",
        sales,
    )
    db.executemany(
        "INSERT INTO fuel_sales VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        fuel_sales,
    )

    # Mirror the production lookup paths: station/status filtering on invoice,
    # sale_id lookup for fuel detail rows, and station/type/deleted/name for parties.
    db.executescript("""
        CREATE INDEX idx_sales_station_deleted
            ON sales_transactions(station_id, is_deleted, id);
        CREATE INDEX idx_fuel_sales_sale
            ON fuel_sales(sale_id);
        CREATE INDEX idx_parties_station_type_deleted_name
            ON parties(station_id, party_type_id, is_deleted, commercial_name);
        ANALYZE;
    """)
    db.commit()
    return db


FUEL_SQL = """
    SELECT fs.id, fs.sale_id, s.station_id, fs.payment_method, fs.total_amount
      FROM fuel_sales fs
      JOIN sales_transactions s ON s.id = fs.sale_id
     WHERE s.station_id = ?
       AND fs.is_deleted = 0
       AND s.is_deleted = 0
       AND (? = '' OR fs.payment_method = ?)
     ORDER BY fs.id DESC
     LIMIT ? OFFSET ?
"""

CUSTOMER_TYPE_SQL = ",".join("'" + code + "'" for code in CUSTOMER_TYPE_CODES)
CUSTOMER_SQL = f"""
    SELECT p.id, p.station_id, p.commercial_name, p.commercial_name_ar
      FROM parties p
     WHERE p.station_id = ?
       AND p.is_deleted = 0
       AND p.party_type_id IN (
            SELECT id
              FROM party_types
             WHERE is_deleted = 0
               AND type_code IN ({CUSTOMER_TYPE_SQL})
       )
     ORDER BY p.commercial_name
     LIMIT ? OFFSET ?
"""


def timed(db, sql, params):
    for _ in range(WARMUP_ITERATIONS):
        db.execute(sql, params).fetchall()

    samples_ms = []
    last_rows = None
    for _ in range(ITERATIONS):
        started = time.perf_counter_ns()
        last_rows = db.execute(sql, params).fetchall()
        samples_ms.append((time.perf_counter_ns() - started) / 1_000_000.0)

    ordered = sorted(samples_ms)
    p95_index = max(0, math.ceil(0.95 * len(ordered)) - 1)
    return last_rows, statistics.median(ordered), ordered[p95_index], len(ordered)


def explain_plan(db, sql, params):
    rows = db.execute("EXPLAIN QUERY PLAN " + sql, params).fetchall()
    return [str(row[3]) for row in rows]


def test_tab_correctness_and_performance(db):
    all_rows, all_median, all_p95, sample_count = timed(
        db, FUEL_SQL, (1, "", "", 500, 0)
    )
    cash_rows, cash_median, cash_p95, _ = timed(
        db, FUEL_SQL, (1, "cash", "cash", 500, 0)
    )
    credit_rows, credit_median, credit_p95, _ = timed(
        db, FUEL_SQL, (1, "credit", "credit", 500, 0)
    )

    assert len(all_rows) == 500, f"expected 500 fuel rows, got {len(all_rows)}"
    assert all(row[2] == 1 for row in all_rows), "fuel query leaked another station"
    assert all(row[3] in {"cash", "credit"} for row in all_rows)
    assert len(cash_rows) == 500 and all(row[3] == "cash" for row in cash_rows)
    assert len(credit_rows) == 500 and all(row[3] == "credit" for row in credit_rows)
    assert not ({row[0] for row in cash_rows} & {row[0] for row in credit_rows})

    # Prove the filter observes a real persisted SQLite value change.
    db.execute("UPDATE fuel_sales SET payment_method='credit' WHERE id=2")
    db.commit()
    changed_cash = db.execute(
        FUEL_SQL, (1, "cash", "cash", SALE_COUNT, 0)
    ).fetchall()
    changed_credit = db.execute(
        FUEL_SQL, (1, "credit", "credit", SALE_COUNT, 0)
    ).fetchall()
    assert not any(row[0] == 2 for row in changed_cash)
    assert any(row[0] == 2 for row in changed_credit)

    return {
        "all": (all_median, all_p95, sample_count),
        "cash": (cash_median, cash_p95, sample_count),
        "credit": (credit_median, credit_p95, sample_count),
    }


def test_customer_performance(db):
    rows, median_ms, p95_ms, sample_count = timed(
        db, CUSTOMER_SQL, (1, 500, 0)
    )
    assert len(rows) == 500
    assert all(row[1] == 1 for row in rows), "customer query leaked another station"
    assert rows[0][2] == "Customer 000001"
    return median_ms, p95_ms, sample_count


def test_query_plans(db):
    # Both production predicates must leave indexed station columns unwrapped.
    assert "CAST(" not in FUEL_SQL.upper()
    assert "CAST(" not in CUSTOMER_SQL.upper()

    fuel_plan = explain_plan(
        db, FUEL_SQL, (1, "cash", "cash", 500, 0)
    )
    customer_plan = explain_plan(
        db, CUSTOMER_SQL, (1, 500, 0)
    )
    fuel_text = " | ".join(fuel_plan).upper()
    customer_text = " | ".join(customer_plan).upper()

    assert "IDX_SALES_STATION_DELETED" in fuel_text, fuel_text
    assert "IDX_FUEL_SALES_SALE" in fuel_text, fuel_text
    assert "IDX_PARTIES_STATION_TYPE_DELETED_NAME" in customer_text, customer_text
    assert "SCAN FUEL_SALES" not in fuel_text, fuel_text
    assert "SCAN PARTIES" not in customer_text, customer_text

    return {"fuel_sales": fuel_plan, "customers": customer_plan}


def assert_latency_budget(name, median_ms, p95_ms):
    assert median_ms < MAX_MEDIAN_MS, (
        f"{name} median exceeded {MAX_MEDIAN_MS:.0f}ms: {median_ms:.2f}ms"
    )
    assert p95_ms < MAX_P95_MS, (
        f"{name} P95 exceeded {MAX_P95_MS:.0f}ms: {p95_ms:.2f}ms"
    )


if __name__ == "__main__":
    started = time.perf_counter()
    database = setup_db()
    plans = test_query_plans(database)
    fuel_timings = test_tab_correctness_and_performance(database)
    customer_timing = test_customer_performance(database)

    print("Fuel-sales / station-scoped customer benchmark: PASS")
    print(
        f"Dataset: parties={PARTY_COUNT:,}, sales_transactions={SALE_COUNT:,}, "
        f"fuel_sales={SALE_COUNT:,}, iterations={ITERATIONS}, warmups={WARMUP_ITERATIONS}"
    )
    print("EXPLAIN QUERY PLAN — fuel sales:")
    for detail in plans["fuel_sales"]:
        print(f"  {detail}")
    print("EXPLAIN QUERY PLAN — customers:")
    for detail in plans["customers"]:
        print(f"  {detail}")

    for name, (median_ms, p95_ms, sample_count) in fuel_timings.items():
        assert_latency_budget(f"fuel/{name}", median_ms, p95_ms)
        print(
            f"fuel/{name}: median={median_ms:.3f}ms "
            f"p95={p95_ms:.3f}ms samples={sample_count}"
        )

    median_ms, p95_ms, sample_count = customer_timing
    assert_latency_budget("customers", median_ms, p95_ms)
    print(
        f"customers: median={median_ms:.3f}ms "
        f"p95={p95_ms:.3f}ms samples={sample_count}"
    )
    print(f"Total benchmark runtime: {time.perf_counter() - started:.2f}s")
