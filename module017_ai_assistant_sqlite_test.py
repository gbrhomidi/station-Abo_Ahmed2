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
        
        CREATE TABLE ai_chat_sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE NOT NULL,
            session_id VARCHAR(100) UNIQUE NOT NULL,
            user_id INTEGER NOT NULL,
            station_id INTEGER,
            title VARCHAR(200),
            provider VARCHAR(50),
            model VARCHAR(100),
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        
        CREATE TABLE ai_chat_messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE NOT NULL,
            session_id VARCHAR(100) NOT NULL,
            role VARCHAR(20) NOT NULL CHECK(role IN ('user', 'assistant', 'system', 'tool')),
            content TEXT NOT NULL,
            tokens_used INTEGER DEFAULT 0,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (session_id) REFERENCES ai_chat_sessions(session_id)
        );
        
        -- Dummy tables for business context tests
        CREATE TABLE sales (id INTEGER PRIMARY KEY, amount DECIMAL, station_id INTEGER);
        INSERT INTO sales VALUES (1, 1000.0, 1), (2, 500.0, 1), (3, 2000.0, 2);
    ''')
    conn.commit()
    return conn

def test_module017_sqlite():
    print("Starting MODULE-017 SQLite integration tests...")
    conn = setup_test_db()
    c = conn.cursor()
    
    try:
        # 1. Test Chat Sessions
        print("1. Testing AI Chat Sessions...")
        session_id = 'session_' + str(uuid.uuid4())
        c.execute("INSERT INTO ai_chat_sessions (uuid, session_id, user_id, station_id, title, provider, model) VALUES (?, ?, ?, ?, ?, ?, ?)",
                  (str(uuid.uuid4()), session_id, 1, 1, 'Sales Analysis', 'openai_compatible', 'gpt-4o-mini'))
        
        c.execute("SELECT COUNT(*) FROM ai_chat_sessions WHERE station_id = 1")
        assert c.fetchone()[0] == 1, "Should have 1 chat session for station 1"
        
        # 2. Test Chat Messages
        print("2. Testing AI Chat Messages...")
        c.executemany("INSERT INTO ai_chat_messages (uuid, session_id, role, content) VALUES (?, ?, ?, ?)", [
            (str(uuid.uuid4()), session_id, 'user', 'What are the total sales?'),
            (str(uuid.uuid4()), session_id, 'assistant', 'The total sales are 1500.0')
        ])
        
        c.execute("SELECT COUNT(*) FROM ai_chat_messages WHERE session_id = ?", (session_id,))
        assert c.fetchone()[0] == 2, "Should have 2 messages in the session"
        
        
        # 3. Test Business Context Data Access and Isolation
        print("3. Testing Business Context Data Access and Isolation...")
        # Simulating a user in station 1 asking for sales
        current_station_id = 1
        c.execute("SELECT SUM(amount) FROM sales WHERE station_id = ?", (current_station_id,))
        total_sales_1 = c.fetchone()[0]
        assert total_sales_1 == 1500.0, "Total sales for station 1 should be 1500.0"
        
        # Simulating a user in station 2 asking for sales
        current_station_id = 2
        c.execute("SELECT SUM(amount) FROM sales WHERE station_id = ?", (current_station_id,))
        total_sales_2 = c.fetchone()[0]
        assert total_sales_2 == 2000.0, "Total sales for station 2 should be 2000.0"
        
        # Simulating isolation check on sessions
        c.execute("SELECT COUNT(*) FROM ai_chat_sessions WHERE station_id = ?", (1,))
        assert c.fetchone()[0] == 1, "Station 1 should see 1 session"
        c.execute("SELECT COUNT(*) FROM ai_chat_sessions WHERE station_id = ?", (2,))
        assert c.fetchone()[0] == 0, "Station 2 should see 0 sessions"
        
        print("All MODULE-017 SQLite tests PASSED!")

    finally:
        conn.close()

if __name__ == "__main__":
    test_module017_sqlite()
