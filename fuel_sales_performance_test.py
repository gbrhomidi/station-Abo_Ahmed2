"""Performance and correctness checks for the fuel-sales tab and customer queries.

The fixture mirrors the production joins and predicates used by getFuelSalesPage
and getParties("customer"). It verifies that changing payment values changes
results at the database boundary and records median/p95 timings.
"""
import sqlite3
import statistics
import time

ITERATIONS = 30
MAX_MEDIAN_MS = 100.0
MAX_P95_MS = 250.0


def setup_db():
    db = sqlite3.connect(":memory:")
    db.executescript("""
        PRAGMA foreign_keys = ON;
        CREATE TABLE parties (
            id INTEGER PRIMARY KEY,
            station_id INTEGER NOT NULL,
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
        CREATE TABLE fuel_types (
            id INTEGER PRIMARY KEY,
            fuel_name TEXT,
            fuel_name_ar TEXT
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
        CREATE INDEX idx_fuel_sales_station_date
            ON fuel_sales(sale_date, is_deleted, payment_method);
        CREATE INDEX idx_fuel_sales_payment
            ON fuel_sales(payment_method, is_deleted);
        CREATE INDEX idx_sales_station_deleted
            ON sales_transactions(station_id, is_deleted, id);
        CREATE INDEX idx_parties_station_deleted_name
            ON parties(station_id, is_deleted, commercial_name);
    """)
    db.execute("INSERT INTO fuel_types VALUES (1, 'Diesel', 'ديزل')")

    parties = []
    for party_id in range(1, 4001):
        station = 1 if party_id <= 3000 else 2
        parties.append((party_id, station, f"Customer {party_id:05d}", f"عميل {party_id:05d}", 0))
    db.executemany("INSERT INTO parties VALUES (?,?,?,?,?)", parties)

    sales = []
    fuel_sales = []
    for sale_id in range(1, 20001):
        station = 1 if sale_id <= 15000 else 2
        payment = "cash" if sale_id % 3 else "credit"
        sales.append((sale_id, station, f"SALE-{sale_id:06d}", f"INV-{sale_id:06d}", 0))
        fuel_sales.append((sale_id, sale_id, 1, 1, 1, 10.0, 1.5, 15.0, payment, "2026-09-02", 0))
    db.executemany("INSERT INTO sales_transactions VALUES (?,?,?,?,?)", sales)
    db.executemany("INSERT INTO fuel_sales VALUES (?,?,?,?,?,?,?,?,?,?,?)", fuel_sales)
    db.commit()
    return db


FUEL_SQL = """
    SELECT fs.id, fs.sale_id, fs.payment_method, fs.total_amount
      FROM fuel_sales fs
      JOIN sales_transactions s ON s.id = fs.sale_id
     WHERE s.station_id = ?
       AND fs.is_deleted = 0
       AND s.is_deleted = 0
       AND (? = '' OR fs.payment_method = ?)
     ORDER BY fs.id DESC
     LIMIT ? OFFSET ?
"""

CUSTOMER_SQL = """
    SELECT id, commercial_name, commercial_name_ar
      FROM parties
     WHERE station_id = ? AND is_deleted = 0
     ORDER BY commercial_name
     LIMIT ? OFFSET ?
"""


def timed(db, sql, params):
    samples = []
    last_rows = None
    for _ in range(ITERATIONS):
        start = time.perf_counter()
        last_rows = db.execute(sql, params).fetchall()
        samples.append((time.perf_counter() - start) * 1000)
    return last_rows, statistics.median(samples), sorted(samples)[int(ITERATIONS * 0.95) - 1]


def test_tab_correctness_and_performance(db):
    all_rows, all_median, all_p95 = timed(db, FUEL_SQL, (1, "", "", 500, 0))
    cash_rows, cash_median, cash_p95 = timed(db, FUEL_SQL, (1, "cash", "cash", 500, 0))
    credit_rows, credit_median, credit_p95 = timed(db, FUEL_SQL, (1, "credit", "credit", 500, 0))

    assert all_rows and len(all_rows) == 500
    assert all(row[2] in {"cash", "credit"} for row in all_rows)
    assert cash_rows and all(row[2] == "cash" for row in cash_rows)
    assert credit_rows and all(row[2] == "credit" for row in credit_rows)
    assert not (set(row[0] for row in cash_rows) & set(row[0] for row in credit_rows))

    # Prove the tab query observes a database value change rather than stale UI data.
    db.execute("UPDATE fuel_sales SET payment_method='credit' WHERE id=2")
    db.commit()
    changed_cash = db.execute(FUEL_SQL, (1, "cash", "cash", 20000, 0)).fetchall()
    changed_credit = db.execute(FUEL_SQL, (1, "credit", "credit", 20000, 0)).fetchall()
    assert not any(row[0] == 2 for row in changed_cash)
    assert any(row[0] == 2 for row in changed_credit)

    timings = {
        "all": (all_median, all_p95),
        "cash": (cash_median, cash_p95),
        "credit": (credit_median, credit_p95),
    }
    for name, (median_ms, p95_ms) in timings.items():
        assert median_ms < MAX_MEDIAN_MS, f"{name} median too slow: {median_ms:.2f}ms"
        assert p95_ms < MAX_P95_MS, f"{name} p95 too slow: {p95_ms:.2f}ms"
    return timings


def test_customer_performance(db):
    rows, median_ms, p95_ms = timed(db, CUSTOMER_SQL, (1, 500, 0))
    assert len(rows) == 500
    assert rows[0][1] == "Customer 00001"
    assert median_ms < MAX_MEDIAN_MS, f"customers median too slow: {median_ms:.2f}ms"
    assert p95_ms < MAX_P95_MS, f"customers p95 too slow: {p95_ms:.2f}ms"
    return median_ms, p95_ms


def test_query_plans(db):
    fuel_plan = " ".join(str(row[-1]).upper() for row in db.execute("EXPLAIN QUERY PLAN " + FUEL_SQL, (1, "cash", "cash", 500, 0)))
    customer_plan = " ".join(str(row[-1]).upper() for row in db.execute("EXPLAIN QUERY PLAN " + CUSTOMER_SQL, (1, 500, 0)))
    assert "SCAN FUEL_SALES" not in fuel_plan
    assert "SCAN PARTIES" not in customer_plan


if __name__ == "__main__":
    database = setup_db()
    tab_timings = test_tab_correctness_and_performance(database)
    customer_timing = test_customer_performance(database)
    test_query_plans(database)
    print("Fuel-sales tab and customer performance tests PASS")
    for name, (median_ms, p95_ms) in tab_timings.items():
        print(f"  {name:>6}: median={median_ms:.2f}ms p95={p95_ms:.2f}ms")
    print(f"customers: median={customer_timing[0]:.2f}ms p95={customer_timing[1]:.2f}ms")
