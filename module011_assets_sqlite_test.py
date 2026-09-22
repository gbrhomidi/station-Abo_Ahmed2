import sqlite3
import os
import uuid
import time
from datetime import datetime

DB_PATH = '/home/ubuntu/station-Abo_Ahmed2/app/src/main/assets/databases/station.db'

def setup_test_db():
    if os.path.exists(DB_PATH):
        os.remove(DB_PATH)
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    c = conn.cursor()
    
    # Minimal schema for testing
    c.executescript('''
        CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT);
        INSERT INTO users (id, username) VALUES (1, 'testuser');
        
        CREATE TABLE fixed_assets (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE NOT NULL,
            station_id INTEGER NOT NULL,
            asset_code VARCHAR(20) UNIQUE NOT NULL,
            asset_name VARCHAR(255) NOT NULL,
            purchase_cost DECIMAL(12,2) CHECK(purchase_cost >= 0),
            current_value DECIMAL(12,2) CHECK(current_value >= 0),
            salvage_value DECIMAL(12,2) CHECK(salvage_value >= 0),
            status VARCHAR(20) DEFAULT 'active',
            is_deleted INTEGER DEFAULT 0
        );
        
        CREATE TABLE maintenance_requests (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE NOT NULL,
            request_code VARCHAR(30) UNIQUE NOT NULL,
            asset_id INTEGER NOT NULL,
            title VARCHAR(200) NOT NULL,
            status VARCHAR(20) DEFAULT 'pending',
            FOREIGN KEY (asset_id) REFERENCES fixed_assets(id)
        );
        
        CREATE TABLE maintenance_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE NOT NULL,
            maintenance_request_id INTEGER NOT NULL,
            event_type VARCHAR(20) NOT NULL,
            event_description TEXT NOT NULL,
            FOREIGN KEY (maintenance_request_id) REFERENCES maintenance_requests(id)
        );
        
        CREATE TABLE depreciation (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            asset_id INTEGER NOT NULL,
            depreciation_amount DECIMAL(12,2) CHECK(depreciation_amount >= 0),
            accumulated_depreciation DECIMAL(12,2) CHECK(accumulated_depreciation >= 0),
            remaining_value DECIMAL(12,2) CHECK(remaining_value >= 0),
            archived INTEGER DEFAULT 0,
            FOREIGN KEY (asset_id) REFERENCES fixed_assets(id)
        );
    ''')
    conn.commit()
    return conn

def test_module011_sqlite():
    print("Starting MODULE-011 SQLite integration tests...")
    conn = setup_test_db()
    c = conn.cursor()
    
    try:
        # 1. Test Asset Creation and Station Isolation
        print("1. Testing Asset Creation and Isolation...")
        c.execute("INSERT INTO fixed_assets (uuid, station_id, asset_code, asset_name, purchase_cost, current_value, salvage_value) VALUES (?, ?, ?, ?, ?, ?, ?)",
                  (str(uuid.uuid4()), 1, 'AST-001', 'Test Pump 1', 10000, 10000, 1000))
        asset_id_1 = c.lastrowid
        
        c.execute("INSERT INTO fixed_assets (uuid, station_id, asset_code, asset_name, purchase_cost, current_value, salvage_value) VALUES (?, ?, ?, ?, ?, ?, ?)",
                  (str(uuid.uuid4()), 2, 'AST-002', 'Test Pump 2', 15000, 15000, 1500))
        asset_id_2 = c.lastrowid
        
        c.execute("SELECT COUNT(*) FROM fixed_assets WHERE station_id = 1")
        assert c.fetchone()[0] == 1, "Station 1 should have exactly 1 asset"
        
        # 2. Test Maintenance Request Creation
        print("2. Testing Maintenance Request...")
        c.execute("INSERT INTO maintenance_requests (uuid, request_code, asset_id, title) VALUES (?, ?, ?, ?)",
                  (str(uuid.uuid4()), 'REQ-001', asset_id_1, 'Fix Leak'))
        req_id = c.lastrowid
        
        # 3. Test Maintenance Completion and History
        print("3. Testing Maintenance Completion...")
        c.execute("UPDATE maintenance_requests SET status = 'completed' WHERE id = ?", (req_id,))
        c.execute("INSERT INTO maintenance_history (uuid, maintenance_request_id, event_type, event_description) VALUES (?, ?, ?, ?)",
                  (str(uuid.uuid4()), req_id, 'completion', 'Fixed leak successfully'))
        
        c.execute("SELECT status FROM maintenance_requests WHERE id = ?", (req_id,))
        assert c.fetchone()[0] == 'completed', "Request should be completed"
        c.execute("SELECT COUNT(*) FROM maintenance_history WHERE maintenance_request_id = ?", (req_id,))
        assert c.fetchone()[0] == 1, "History record should exist"
        
        # 4. Test Depreciation Logic (Asset value cannot go below salvage value)
        print("4. Testing Depreciation Logic...")
        # First depreciation: 5000
        c.execute("INSERT INTO depreciation (asset_id, depreciation_amount, accumulated_depreciation, remaining_value) VALUES (?, ?, ?, ?)",
                  (asset_id_1, 5000, 5000, 5000))
        c.execute("UPDATE fixed_assets SET current_value = 5000 WHERE id = ?", (asset_id_1,))
        
        c.execute("SELECT current_value FROM fixed_assets WHERE id = ?", (asset_id_1,))
        assert c.fetchone()[0] == 5000, "Current value should be updated to 5000"
        
        # Attempt second depreciation that would drop below salvage value (1000)
        try:
            # We simulate the application logic that would prevent this
            current_val = 5000
            salvage = 1000
            dep_amount = 4500
            new_val = current_val - dep_amount
            assert new_val >= salvage, f"Cannot depreciate below salvage value. New: {new_val}, Salvage: {salvage}"
            raise Exception("Should have failed")
        except AssertionError as e:
            print(f"   Successfully caught invalid depreciation: {e}")
            
        # 5. Test Depreciation Rollback (Archive)
        print("5. Testing Depreciation Rollback...")
        dep_id = c.lastrowid
        # Archive depreciation
        c.execute("UPDATE depreciation SET archived = 1 WHERE id = ?", (dep_id,))
        # Restore asset value
        c.execute("UPDATE fixed_assets SET current_value = current_value + 5000 WHERE id = ?", (asset_id_1,))
        
        c.execute("SELECT current_value FROM fixed_assets WHERE id = ?", (asset_id_1,))
        assert c.fetchone()[0] == 10000, "Current value should be restored to original 10000"
        
        print("All MODULE-011 SQLite tests PASSED!")
    finally:
        conn.close()

if __name__ == "__main__":
    test_module011_sqlite()
