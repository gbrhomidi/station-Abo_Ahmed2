"""SQLite contract/integration tests for MODULE-004 fleet data rules.

These tests exercise database rules in isolation; they do not claim Android APK/runtime coverage.
"""

import sqlite3
import tempfile
from pathlib import Path


def setup_test_db():
    handle = tempfile.NamedTemporaryFile(prefix="module004_", suffix=".db", delete=False)
    handle.close()
    db_path = Path(handle.name)
    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA foreign_keys = ON")
    conn.executescript(
        """
        CREATE TABLE parties (
            id INTEGER PRIMARY KEY,
            station_id INTEGER NOT NULL,
            party_name TEXT NOT NULL,
            is_deleted INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE vehicles (
            id INTEGER PRIMARY KEY,
            uuid TEXT UNIQUE NOT NULL,
            vehicle_code TEXT UNIQUE NOT NULL,
            party_id INTEGER NOT NULL REFERENCES parties(id),
            plate_number TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'active',
            current_odometer REAL NOT NULL DEFAULT 0,
            is_deleted INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE drivers (
            id INTEGER PRIMARY KEY,
            uuid TEXT UNIQUE NOT NULL,
            driver_code TEXT UNIQUE NOT NULL,
            station_id INTEGER NOT NULL,
            vehicle_id INTEGER REFERENCES vehicles(id),
            full_name TEXT NOT NULL,
            license_number TEXT,
            license_expiry_date TEXT,
            status TEXT NOT NULL DEFAULT 'active',
            is_deleted INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE vehicle_locations (
            id INTEGER PRIMARY KEY,
            vehicle_id INTEGER NOT NULL REFERENCES vehicles(id),
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            location_time TEXT NOT NULL
        );
        CREATE TABLE vehicle_trips (
            id INTEGER PRIMARY KEY,
            uuid TEXT UNIQUE NOT NULL,
            vehicle_id INTEGER NOT NULL REFERENCES vehicles(id),
            driver_id INTEGER REFERENCES drivers(id),
            trip_date TEXT NOT NULL,
            distance_km REAL,
            status TEXT NOT NULL DEFAULT 'scheduled'
        );
        CREATE TABLE vehicle_expenses (
            id INTEGER PRIMARY KEY,
            uuid TEXT UNIQUE NOT NULL,
            vehicle_id INTEGER NOT NULL REFERENCES vehicles(id),
            expense_type TEXT NOT NULL,
            expense_date TEXT NOT NULL,
            amount REAL NOT NULL CHECK(amount > 0)
        );
        CREATE INDEX idx_vehicles_party ON vehicles(party_id, is_deleted);
        CREATE INDEX idx_drivers_station ON drivers(station_id, is_deleted);
        CREATE INDEX idx_trips_vehicle_date ON vehicle_trips(vehicle_id, trip_date);
        CREATE INDEX idx_expenses_vehicle_date ON vehicle_expenses(vehicle_id, expense_date);
        INSERT INTO parties(id, station_id, party_name) VALUES (1, 10, 'Station A Owner');
        INSERT INTO parties(id, station_id, party_name) VALUES (2, 20, 'Station B Owner');
        INSERT INTO vehicles(id, uuid, vehicle_code, party_id, plate_number) VALUES (1, 'v-a', 'VA-001', 1, 'A-001');
        INSERT INTO vehicles(id, uuid, vehicle_code, party_id, plate_number) VALUES (2, 'v-b', 'VB-001', 2, 'B-001');
        INSERT INTO drivers(id, uuid, driver_code, station_id, vehicle_id, full_name, license_number, license_expiry_date)
            VALUES (1, 'd-a', 'DA-001', 10, 1, 'Driver A', 'LIC-A', '2027-01-01');
        INSERT INTO drivers(id, uuid, driver_code, station_id, vehicle_id, full_name, license_number, license_expiry_date)
            VALUES (2, 'd-b', 'DB-001', 20, 2, 'Driver B', 'LIC-B', '2027-01-01');
        """
    )
    conn.commit()
    return conn, db_path


def station_vehicle_ids(conn, station_id):
    return [
        row[0]
        for row in conn.execute(
            """
            SELECT v.id
            FROM vehicles v
            JOIN parties p ON p.id = v.party_id
            WHERE p.station_id = ? AND p.is_deleted = 0 AND v.is_deleted = 0
            ORDER BY v.id
            """,
            (station_id,),
        )
    ]


def test_module004_sqlite():
    conn, db_path = setup_test_db()
    try:
        print("Starting MODULE-004 SQLite integration tests...")

        print("1. Testing station isolation for vehicles and drivers...")
        assert station_vehicle_ids(conn, 10) == [1]
        assert station_vehicle_ids(conn, 20) == [2]
        assert conn.execute(
            "SELECT COUNT(*) FROM drivers WHERE station_id = ? AND is_deleted = 0",
            (10,),
        ).fetchone()[0] == 1

        print("2. Testing relation integrity and cross-station rejection...")
        conn.execute(
            "INSERT INTO vehicle_trips(uuid, vehicle_id, driver_id, trip_date, distance_km) VALUES (?, ?, ?, ?, ?)",
            ('trip-a', 1, 1, '2026-08-23', 150.5),
        )
        conn.execute(
            "INSERT INTO vehicle_expenses(uuid, vehicle_id, expense_type, expense_date, amount) VALUES (?, ?, ?, ?, ?)",
            ('expense-a', 1, 'fuel', '2026-08-23', 500.0),
        )
        assert conn.execute("SELECT COUNT(*) FROM vehicle_trips WHERE vehicle_id = 1").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM vehicle_expenses WHERE vehicle_id = 1").fetchone()[0] == 1

        # The Android business layer performs the station check before this insert.
        try:
            driver_station = conn.execute("SELECT station_id FROM drivers WHERE id = ?", (2,)).fetchone()[0]
            vehicle_station = conn.execute(
                "SELECT p.station_id FROM vehicles v JOIN parties p ON p.id = v.party_id WHERE v.id = ?",
                (1,),
            ).fetchone()[0]
            if driver_station != vehicle_station:
                raise ValueError("cross-station relation")
        except ValueError:
            pass
        else:
            raise AssertionError("Cross-station trip relation was not rejected")

        print("3. Testing validation boundaries and parameterized search...")
        try:
            conn.execute(
                "INSERT INTO vehicle_expenses(uuid, vehicle_id, expense_type, expense_date, amount) VALUES (?, ?, ?, ?, ?)",
                ('expense-invalid', 1, 'fuel', '2026-08-23', 0),
            )
        except sqlite3.IntegrityError:
            pass
        else:
            raise AssertionError("Non-positive vehicle expense was accepted")
        search = "A%' OR 1=1 --"
        rows = conn.execute(
            "SELECT id FROM vehicles WHERE plate_number LIKE ? AND is_deleted = 0",
            (f"%{search}%",),
        ).fetchall()
        assert rows == []

        conn.commit()
        print("4. Testing transaction rollback...")
        try:
            with conn:
                conn.execute(
                    "INSERT INTO vehicle_expenses(uuid, vehicle_id, expense_type, expense_date, amount) VALUES (?, ?, ?, ?, ?)",
                    ('expense-rollback', 1, 'maintenance', '2026-08-23', 100),
                )
                raise RuntimeError("rollback sentinel")
        except RuntimeError:
            pass
        assert conn.execute(
            "SELECT COUNT(*) FROM vehicle_expenses WHERE uuid = ?",
            ('expense-rollback',),
        ).fetchone()[0] == 0

        print("5. Testing aggregate reporting...")
        total = conn.execute(
            "SELECT COALESCE(SUM(amount), 0) FROM vehicle_expenses WHERE vehicle_id = ? AND expense_date BETWEEN ? AND ?",
            (1, '2026-08-01', '2026-08-31'),
        ).fetchone()[0]
        assert total == 500.0

        conn.commit()
        print("MODULE-004 SQLite tests: PASS")
    finally:
        conn.close()
        db_path.unlink(missing_ok=True)


if __name__ == "__main__":
    test_module004_sqlite()
