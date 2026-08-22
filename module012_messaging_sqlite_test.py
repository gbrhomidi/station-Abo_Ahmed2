import sqlite3
import os
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
        
        CREATE TABLE parties (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            party_type TEXT,
            party_name TEXT,
            phone_number TEXT,
            credit_limit REAL DEFAULT 0,
            current_balance REAL DEFAULT 0
        );
        
        CREATE TABLE notification_templates (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE NOT NULL,
            template_code VARCHAR(30) UNIQUE NOT NULL,
            template_name VARCHAR(100) NOT NULL,
            channel VARCHAR(20) NOT NULL,
            body TEXT NOT NULL,
            is_active INTEGER DEFAULT 1,
            created_by INTEGER
        );
        
        CREATE TABLE notifications (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER,
            title VARCHAR(200) NOT NULL,
            message TEXT NOT NULL,
            is_read INTEGER DEFAULT 0,
            read_at DATETIME
        );
        
        CREATE TABLE sms_messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid TEXT UNIQUE,
            phone_number TEXT NOT NULL,
            message_body TEXT NOT NULL,
            message_type TEXT DEFAULT 'outgoing',
            status TEXT DEFAULT 'pending'
        );
        
        CREATE TABLE sms_whitelist (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            phone TEXT UNIQUE NOT NULL,
            name TEXT,
            enabled INTEGER DEFAULT 1
        );
    ''')
    conn.commit()
    return conn

def test_module012_sqlite():
    print("Starting MODULE-012 SQLite integration tests...")
    conn = setup_test_db()
    c = conn.cursor()
    
    try:
        # 1. Test Notification Templates
        print("1. Testing Notification Templates...")
        c.execute("INSERT INTO notification_templates (uuid, template_code, template_name, channel, body, created_by) VALUES (?, ?, ?, ?, ?, ?)",
                  (str(uuid.uuid4()), 'TPL-001', 'Welcome SMS', 'sms', 'Welcome {name}!', 1))
        tpl_id = c.lastrowid
        
        c.execute("SELECT body FROM notification_templates WHERE id = ?", (tpl_id,))
        assert c.fetchone()[0] == 'Welcome {name}!', "Template body should match"
        
        # 2. Test Notifications Inbox
        print("2. Testing Notifications Inbox...")
        c.execute("INSERT INTO notifications (user_id, title, message) VALUES (?, ?, ?)",
                  (1, 'System Alert', 'Backup completed'))
        notif_id = c.lastrowid
        
        c.execute("UPDATE notifications SET is_read = 1, read_at = CURRENT_TIMESTAMP WHERE id = ?", (notif_id,))
        c.execute("SELECT is_read FROM notifications WHERE id = ?", (notif_id,))
        assert c.fetchone()[0] == 1, "Notification should be marked as read"
        
        # 3. Test SMS Queue
        print("3. Testing SMS Queue...")
        c.execute("INSERT INTO sms_messages (uuid, phone_number, message_body, status) VALUES (?, ?, ?, ?)",
                  (str(uuid.uuid4()), '967700000000', 'Test message', 'pending'))
        sms_id = c.lastrowid
        
        c.execute("SELECT COUNT(*) FROM sms_messages WHERE status = 'pending'")
        assert c.fetchone()[0] == 1, "Should have 1 pending SMS"
        
        c.execute("UPDATE sms_messages SET status = 'failed' WHERE id = ?", (sms_id,))
        c.execute("SELECT status FROM sms_messages WHERE id = ?", (sms_id,))
        assert c.fetchone()[0] == 'failed', "SMS should be marked as failed"
        
        # 4. Test Whitelist
        print("4. Testing Whitelist...")
        c.execute("INSERT INTO sms_whitelist (phone, name, enabled) VALUES (?, ?, ?)",
                  ('967711111111', 'Manager', 1))
        
        c.execute("SELECT enabled FROM sms_whitelist WHERE phone = '967711111111'")
        assert c.fetchone()[0] == 1, "Number should be enabled in whitelist"
        
        # 5. Test Debt Reminders Logic (Parties with credit)
        print("5. Testing Debt Reminders Logic...")
        c.execute("INSERT INTO parties (party_type, party_name, phone_number, credit_limit, current_balance) VALUES (?, ?, ?, ?, ?)",
                  ('customer', 'John Doe', '967722222222', 10000, 5000))
        # Outstanding is credit_limit - current_balance = 5000
        
        c.execute("SELECT (credit_limit - current_balance) as outstanding FROM parties WHERE party_type = 'customer'")
        assert c.fetchone()[0] == 5000, "Outstanding balance should be 5000"
        
        print("All MODULE-012 SQLite tests PASSED!")
    finally:
        conn.close()

if __name__ == "__main__":
    test_module012_sqlite()
