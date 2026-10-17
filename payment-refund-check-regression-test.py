#!/usr/bin/env python3
"""Regression test for the payments.payment_type CHECK and credit-sale refund mapping."""
from pathlib import Path
import re
import sqlite3
import sys

SOURCE = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(
    "app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/DatabaseHelper.kt"
)
source = SOURCE.read_text(encoding="utf-8")
match = re.search(
    r"CREATE TABLE IF NOT EXISTS payments\s*\((.*?)\n\s*\);",
    source,
    flags=re.IGNORECASE | re.DOTALL,
)
if not match:
    raise SystemExit(f"Could not extract payments DDL from {SOURCE}")

ddl = "CREATE TABLE payments (" + match.group(1) + ");"
db = sqlite3.connect(":memory:")
db.execute(ddl)

db.execute(
    """INSERT INTO payments
       (uuid, payment_code, payment_type, payment_method, amount, status, is_refund)
       VALUES (?, ?, ?, ?, ?, ?, ?)""",
    ("original", "ORIGINAL-1", "cash", "credit", 60.0, "completed", 0),
)
db.execute(
    """INSERT INTO payments
       (uuid, payment_code, payment_type, payment_method, amount, status, is_refund)
       VALUES (?, ?, ?, ?, ?, ?, ?)""",
    ("refund", "REFUND-1", "cash", "credit", 25.0, "refunded", 1),
)
row = db.execute(
    "SELECT payment_type, payment_method FROM payments WHERE payment_code='REFUND-1'"
).fetchone()
assert row == ("cash", "credit"), f"Expected separate payment fields, got {row!r}"

try:
    db.execute(
        """INSERT INTO payments
           (uuid, payment_code, payment_type, payment_method, amount, status, is_refund)
           VALUES (?, ?, ?, ?, ?, ?, ?)""",
        ("bad-refund", "REFUND-BAD", "credit", "credit", 25.0, "refunded", 1),
    )
except sqlite3.IntegrityError as exc:
    assert "CHECK constraint failed" in str(exc), str(exc)
else:
    raise AssertionError("Schema unexpectedly accepted payment_type='credit'")

print("PASS: credit-sale refund fields preserve payment_type='cash' and payment_method='credit'")
print("PASS: schema rejects payment_type='credit'")
