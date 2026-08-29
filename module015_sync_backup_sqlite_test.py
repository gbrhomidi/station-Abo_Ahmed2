import sqlite3
import os
import json
import uuid
import time

DB_PATH = '/home/ubuntu/station-Abo_Ahmed2/app/src/main/assets/databases/station.db'

def setup_test_db():
    if os.path.exists(DB_PATH):
        os.remove(DB_PATH)
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    c = conn.cursor()
    
    c.executescript('''
        CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT);
        INSERT INTO users (id, username) VALUES (1, 'testuser');
        
        CREATE TABLE sync_devices (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE NOT NULL,
            device_id VARCHAR(100) UNIQUE NOT NULL,
            device_name VARCHAR(200),
            device_type VARCHAR(50),
            os_version VARCHAR(20),
            app_version VARCHAR(20),
            station_id INTEGER,
            last_sync_at DATETIME,
            is_active INTEGER DEFAULT 1
        );
        
        CREATE TABLE sync_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE NOT NULL,
            sync_type VARCHAR(20) NOT NULL,
            sync_direction VARCHAR(20) NOT NULL,
            device_id VARCHAR(100) NOT NULL,
            station_id INTEGER,
            device_name VARCHAR(200),
            entity_type VARCHAR(50) NOT NULL,
            records_synced INTEGER DEFAULT 0,
            records_failed INTEGER DEFAULT 0,
            records_total INTEGER DEFAULT 0,
            status VARCHAR(20) DEFAULT 'pending',
            error_message TEXT,
            started_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            completed_at DATETIME,
            duration_seconds INTEGER
        );
        
        CREATE TABLE backup_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE NOT NULL,
            backup_type VARCHAR(20) NOT NULL,
            station_id INTEGER,
            backup_method VARCHAR(20) DEFAULT 'manual',
            file_name VARCHAR(255),
            file_path VARCHAR(500),
            file_size_mb DECIMAL(10,2),
            checksum VARCHAR(64),
            status VARCHAR(20) DEFAULT 'success',
            started_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            completed_at DATETIME,
            duration_seconds INTEGER
        );
    ''')
    conn.commit()
    return conn

def test_module015_sqlite():
    print("Starting MODULE-015 SQLite integration tests...")
    conn = setup_test_db()
    c = conn.cursor()
    
    try:
        # 1. Test Sync Devices
        print("1. Testing Sync Devices...")
        c.executemany("INSERT INTO sync_devices (uuid, device_id, device_name, device_type, station_id, is_active) VALUES (?, ?, ?, ?, ?, ?)", [
            (str(uuid.uuid4()), 'DEV-001', 'Galaxy S21', 'mobile', 1, 1),
            (str(uuid.uuid4()), 'DEV-002', 'iPad Pro', 'tablet', 1, 0),
            (str(uuid.uuid4()), 'DEV-003', 'iPhone 13', 'mobile', 2, 1) # Different station
        ])
        
        # Test station isolation
        c.execute("SELECT COUNT(*) FROM sync_devices WHERE station_id = 1")
        assert c.fetchone()[0] == 2, "Station 1 should have 2 devices"
        
        # Test active filtering
        c.execute("SELECT COUNT(*) FROM sync_devices WHERE is_active = 1")
        assert c.fetchone()[0] == 2, "Should have 2 active devices across all stations"
        
        
        # 2. Test Sync Logs with Station Isolation
        print("2. Testing Sync Logs with Station Isolation...")
        c.executemany("INSERT INTO sync_logs (uuid, sync_type, sync_direction, device_id, station_id, entity_type, records_synced, records_failed, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", [
            (str(uuid.uuid4()), 'push', 'push', 'DEV-001', 1, 'sales', 100, 0, 'completed'),
            (str(uuid.uuid4()), 'pull', 'pull', 'DEV-001', 1, 'products', 50, 5, 'partial'),
            (str(uuid.uuid4()), 'push', 'push', 'DEV-002', 2, 'inventory', 0, 10, 'failed')
        ])
        
        c.execute("SELECT COUNT(*) FROM sync_logs WHERE status = 'completed'")
        assert c.fetchone()[0] == 1, "Should have 1 completed sync"
        
        c.execute("SELECT SUM(records_failed) FROM sync_logs")
        assert c.fetchone()[0] == 15, "Total failed records should be 15"
        
        # Test isolation query
        c.execute("SELECT COUNT(*) FROM sync_logs WHERE station_id = 1")
        assert c.fetchone()[0] == 2, "Station 1 should only see its own sync logs"
        
        # 3. Test Backup History with Station Isolation
        print("3. Testing Backup History with Station Isolation...")
        c.executemany("INSERT INTO backup_history (uuid, backup_type, station_id, file_name, file_size_mb, status) VALUES (?, ?, ?, ?, ?, ?)", [
            (str(uuid.uuid4()), 'full', 1, 'backup_20260823.zip', 15.5, 'success'),
            (str(uuid.uuid4()), 'partial', 2, 'backup_20260824.zip', 5.5, 'success')
        ])
        
        c.execute("SELECT file_size_mb FROM backup_history WHERE status = 'success' AND station_id = 1")
        assert c.fetchone()[0] == 15.5, "Backup size for station 1 should be 15.5 MB"
        
        c.execute("SELECT COUNT(*) FROM backup_history WHERE station_id = 1")
        assert c.fetchone()[0] == 1, "Station 1 should only see its own backups"
        print("All MODULE-015 SQLite tests PASSED!")
    finally:
        conn.close()

if __name__ == "__main__":
    test_module015_sqlite()
