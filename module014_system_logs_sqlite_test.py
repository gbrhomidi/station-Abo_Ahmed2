import sqlite3
import os
import json
import uuid

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
        
        CREATE TABLE system_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE NOT NULL,
            log_level VARCHAR(10) NOT NULL,
            log_type VARCHAR(30) NOT NULL,
            source VARCHAR(100),
            message TEXT NOT NULL,
            station_id INTEGER,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        
        CREATE TABLE audit_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE NOT NULL,
            user_id INTEGER,
            action_type VARCHAR(50) NOT NULL,
            table_name VARCHAR(50) NOT NULL,
            record_id INTEGER,
            old_row_json TEXT,
            new_row_json TEXT,
            changed_columns TEXT,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        
        CREATE TABLE documents (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE NOT NULL,
            document_code VARCHAR(30) UNIQUE NOT NULL,
            document_name VARCHAR(200) NOT NULL,
            document_type VARCHAR(30) NOT NULL,
            entity_type VARCHAR(50) NOT NULL,
            entity_id INTEGER NOT NULL,
            file_name VARCHAR(255) NOT NULL,
            file_path VARCHAR(500) NOT NULL,
            file_size INTEGER,
            mime_type VARCHAR(100),
            uploaded_by INTEGER NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
    ''')
    conn.commit()
    return conn

def test_module014_sqlite():
    print("Starting MODULE-014 SQLite integration tests...")
    conn = setup_test_db()
    c = conn.cursor()
    
    try:
        # 1. Test System Logs
        print("1. Testing System Logs...")
        c.executemany("INSERT INTO system_logs (uuid, log_level, log_type, source, message, station_id) VALUES (?, ?, ?, ?, ?, ?)", [
            (str(uuid.uuid4()), 'info', 'auth', 'login', 'User logged in successfully', 1),
            (str(uuid.uuid4()), 'error', 'database', 'query', 'Failed to execute query', 1),
            (str(uuid.uuid4()), 'warning', 'network', 'sync', 'Sync delayed', 2) # Different station
        ])
        
        # Test station isolation
        c.execute("SELECT COUNT(*) FROM system_logs WHERE station_id = 1")
        assert c.fetchone()[0] == 2, "Station 1 should have 2 logs"
        
        # Test level filtering
        c.execute("SELECT COUNT(*) FROM system_logs WHERE log_level = 'error'")
        assert c.fetchone()[0] == 1, "Should have 1 error log"
        
        # 2. Test Audit Logs
        print("2. Testing Audit Logs...")
        old_data = json.dumps({"price": 10})
        new_data = json.dumps({"price": 15})
        changed = json.dumps(["price"])
        
        c.execute("INSERT INTO audit_logs (uuid, user_id, action_type, table_name, record_id, old_row_json, new_row_json, changed_columns) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                  (str(uuid.uuid4()), 1, 'UPDATE', 'products', 101, old_data, new_data, changed))
        
        c.execute("SELECT old_row_json, new_row_json FROM audit_logs WHERE table_name = 'products'")
        row = c.fetchone()
        assert json.loads(row[0])["price"] == 10, "Old price should be 10"
        assert json.loads(row[1])["price"] == 15, "New price should be 15"
        
        # 3. Test Documents
        print("3. Testing Documents Management...")
        c.execute("INSERT INTO documents (uuid, document_code, document_name, document_type, entity_type, entity_id, file_name, file_path, file_size, mime_type, uploaded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                  (str(uuid.uuid4()), 'DOC-001', 'Contract A', 'contract', 'customer', 5, 'contract_a.pdf', '/storage/docs/contract_a.pdf', 1024000, 'application/pdf', 1))
        doc_id = c.lastrowid
        
        c.execute("SELECT file_path, mime_type FROM documents WHERE id = ?", (doc_id,))
        row = c.fetchone()
        assert row[0] == '/storage/docs/contract_a.pdf', "File path should match"
        assert row[1] == 'application/pdf', "MIME type should match"
        
        print("All MODULE-014 SQLite tests PASSED!")
    finally:
        conn.close()

if __name__ == "__main__":
    test_module014_sqlite()
