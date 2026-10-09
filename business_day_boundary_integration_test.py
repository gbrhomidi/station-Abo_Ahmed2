"""Regression test for the Riyadh business-day boundary.

This test exercises the SQLite predicates used by operational reports. It deliberately
stores UTC-looking created_at values around 00:00–02:59 Riyadh and verifies that the
stored business_day, not created_at, selects the operation.
"""
from datetime import datetime, timezone
from pathlib import Path
import sqlite3
from zoneinfo import ZoneInfo

RIYADH = ZoneInfo("Asia/Riyadh")


def business_day(instant: datetime) -> str:
    return instant.astimezone(RIYADH).date().isoformat()


def setup_db() -> sqlite3.Connection:
    db = sqlite3.connect(":memory:")
    db.executescript(
        """
        CREATE TABLE sales_transactions (
            id INTEGER PRIMARY KEY, station_id INTEGER NOT NULL,
            created_at TEXT NOT NULL, business_day TEXT NOT NULL,
            net_amount REAL NOT NULL, is_deleted INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE fuel_sales (
            id INTEGER PRIMARY KEY, station_id INTEGER NOT NULL,
            created_at TEXT NOT NULL, business_day TEXT NOT NULL,
            quantity REAL NOT NULL, is_deleted INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE payments (
            id INTEGER PRIMARY KEY, station_id INTEGER NOT NULL,
            created_at TEXT NOT NULL, business_day TEXT NOT NULL,
            amount REAL NOT NULL, is_deleted INTEGER NOT NULL DEFAULT 0
        );
        """
    )
    return db


def test_midnight_to_three_is_same_operating_day() -> None:
    db = setup_db()
    # 00:30 and 02:59 Riyadh are the previous UTC calendar date.
    instants = [
        datetime(2026, 10, 9, 0, 30, tzinfo=RIYADH),
        datetime(2026, 10, 9, 2, 59, 59, tzinfo=RIYADH),
        datetime(2026, 10, 9, 3, 0, tzinfo=RIYADH),
    ]
    for index, local_time in enumerate(instants, 1):
        utc_text = local_time.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
        day = business_day(local_time.astimezone(timezone.utc))
        db.execute(
            "INSERT INTO sales_transactions VALUES (?, 7, ?, ?, ?, 0)",
            (index, utc_text, day, index * 10),
        )
        db.execute(
            "INSERT INTO fuel_sales VALUES (?, 7, ?, ?, ?, 0)",
            (index, utc_text, day, index * 5),
        )
        db.execute(
            "INSERT INTO payments VALUES (?, 7, ?, ?, ?, 0)",
            (index, utc_text, day, index * 3),
        )
    db.commit()

    day = "2026-10-09"
    sales = db.execute(
        "SELECT COUNT(*), COALESCE(SUM(net_amount), 0) "
        "FROM sales_transactions WHERE station_id=? AND business_day=? AND is_deleted=0",
        (7, day),
    ).fetchone()
    fuel = db.execute(
        "SELECT COUNT(*), COALESCE(SUM(quantity), 0) "
        "FROM fuel_sales WHERE station_id=? AND business_day=? AND is_deleted=0",
        (7, day),
    ).fetchone()
    payments = db.execute(
        "SELECT COUNT(*), COALESCE(SUM(amount), 0) "
        "FROM payments WHERE station_id=? AND business_day=? AND is_deleted=0",
        (7, day),
    ).fetchone()
    assert sales == (3, 60.0), sales
    assert fuel == (3, 30.0), fuel
    assert payments == (3, 18.0), payments

    # Demonstrate the regression: UTC date filtering would omit the first two rows.
    utc_count = db.execute(
        "SELECT COUNT(*) FROM sales_transactions "
        "WHERE station_id=? AND substr(created_at,1,10)=?",
        (7, day),
    ).fetchone()[0]
    assert utc_count == 1, utc_count


def test_source_uses_business_day_for_sales_reports() -> None:
    source = Path("app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt").read_text()
    required = [
        "WHERE s.business_day = ?",
        "s.business_day BETWEEN date(?) AND date(?)",
        "sx.business_day BETWEEN date(?) AND date(?)",
        "fs.business_day BETWEEN date(?) AND date(?)",
        "SELECT business_day as day",
    ]
    for fragment in required:
        assert fragment in source, f"missing business-day query fragment: {fragment}"


if __name__ == "__main__":
    test_midnight_to_three_is_same_operating_day()
    test_source_uses_business_day_for_sales_reports()
    print("BUSINESS-DAY BOUNDARY INTEGRATION PASS")
