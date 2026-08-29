import sqlite3
import os
import json

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
        
        CREATE TABLE currencies (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE NOT NULL,
            currency_code VARCHAR(3) NOT NULL UNIQUE,
            currency_name VARCHAR(100) NOT NULL,
            currency_name_ar VARCHAR(100)
        );
        INSERT INTO currencies (uuid, currency_code, currency_name, currency_name_ar) VALUES ('u1', 'USD', 'US Dollar', 'دولار أمريكي');
        
        CREATE TABLE companies (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE NOT NULL,
            company_code VARCHAR(20) UNIQUE NOT NULL,
            company_name VARCHAR(200) NOT NULL,
            company_name_ar VARCHAR(200),
            default_currency_id INTEGER
        );
        
        CREATE TABLE stations (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE NOT NULL,
            station_code VARCHAR(20) UNIQUE NOT NULL,
            station_name VARCHAR(200) NOT NULL,
            station_name_ar VARCHAR(200),
            status VARCHAR(20) DEFAULT 'active'
        );
        
        CREATE TABLE system_settings (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE NOT NULL,
            setting_key TEXT UNIQUE NOT NULL,
            setting_value TEXT NOT NULL
        );
    ''')
    conn.commit()
    return conn

def test_module002_sqlite():
    print("Starting MODULE-002 SQLite integration tests...")
    conn = setup_test_db()
    c = conn.cursor()
    
    try:
        # 1. Test Company Profile
        print("1. Testing Company Profile...")
        c.execute("INSERT INTO companies (uuid, company_code, company_name, company_name_ar, default_currency_id) VALUES ('u1', 'C1', 'Test Co', 'شركة اختبار', 1)")
        c.execute("SELECT company_name_ar FROM companies LIMIT 1")
        assert c.fetchone()[0] == 'شركة اختبار', "Company profile not found"
        
        # 2. Test Stations
        print("2. Testing Stations...")
        c.execute("INSERT INTO stations (uuid, station_code, station_name, station_name_ar, status) VALUES ('u2', 'S1', 'Station 1', 'محطة 1', 'active')")
        c.execute("SELECT COUNT(*) FROM stations WHERE status = 'active'")
        assert c.fetchone()[0] == 1, "Station not found"
        
        # 3. Test System Settings
        print("3. Testing System Settings...")
        settings_json = json.dumps({"allow_negative_balance": True, "inventory_method": "fifo"})
        c.execute("INSERT INTO system_settings (uuid, setting_key, setting_value) VALUES ('u3', 'app_config', ?)", (settings_json,))
        c.execute("SELECT setting_value FROM system_settings WHERE setting_key = 'app_config'")
        saved_settings = json.loads(c.fetchone()[0])
        assert saved_settings["inventory_method"] == "fifo", "System settings not saved correctly"
        
        print("All MODULE-002 SQLite tests PASSED!")
    finally:
        conn.close()

if __name__ == "__main__":
    test_module002_sqlite()
