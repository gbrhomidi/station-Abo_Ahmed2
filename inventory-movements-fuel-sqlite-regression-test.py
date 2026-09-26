import sqlite3
import unittest

class FuelMovementAtomicityTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("""
            CREATE TABLE tanks(id INTEGER PRIMARY KEY, station_id INTEGER, fuel_type_id INTEGER,
                               current_quantity REAL NOT NULL, capacity_liters REAL NOT NULL,
                               is_deleted INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'active');
            CREATE TABLE tank_ledger(id INTEGER PRIMARY KEY AUTOINCREMENT, tank_id INTEGER,
                               transaction_date TEXT, transaction_type TEXT, debit REAL DEFAULT 0,
                               credit REAL DEFAULT 0, balance REAL, description TEXT);
            INSERT INTO tanks VALUES (10, 1, 7, 100, 1000, 0, 'active');
        """)

    def move(self, quantity, kind, force_failure=False):
        self.db.execute("BEGIN")
        try:
            before = self.db.execute("SELECT current_quantity FROM tanks WHERE id=10").fetchone()[0]
            signed = quantity if kind == "in" else -quantity
            after = before + signed
            if after < 0 or after > 1000:
                raise ValueError("invalid balance")
            self.db.execute(
                "INSERT INTO tank_ledger(tank_id,transaction_date,transaction_type,debit,credit,balance) VALUES(10,'2026-09-26','manual_'||?, ?, ?, ?)",
                (kind, max(signed,0), max(-signed,0), after)
            )
            self.db.execute("UPDATE tanks SET current_quantity=? WHERE id=10", (after,))
            if force_failure:
                raise RuntimeError("forced failure")
            self.db.commit()
        except Exception:
            self.db.rollback()
            raise

    def test_in_and_out(self):
        self.move(25, "in")
        self.assertEqual(self.db.execute("SELECT current_quantity FROM tanks WHERE id=10").fetchone()[0], 125)
        self.move(20, "out")
        self.assertEqual(self.db.execute("SELECT current_quantity FROM tanks WHERE id=10").fetchone()[0], 105)

    def test_negative_stock_rejected(self):
        with self.assertRaises(ValueError):
            self.move(101, "out")
        self.assertEqual(self.db.execute("SELECT current_quantity FROM tanks WHERE id=10").fetchone()[0], 100)

    def test_atomic_rollback(self):
        with self.assertRaises(RuntimeError):
            self.move(30, "in", force_failure=True)
        self.assertEqual(self.db.execute("SELECT current_quantity FROM tanks WHERE id=10").fetchone()[0], 100)
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM tank_ledger").fetchone()[0], 0)

if __name__ == "__main__":
    unittest.main()
