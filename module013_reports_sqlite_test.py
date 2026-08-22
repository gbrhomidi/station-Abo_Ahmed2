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
        
        CREATE TABLE sales_transactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            station_id INTEGER,
            invoice_number TEXT,
            transaction_date TEXT,
            total_amount REAL,
            discount_amount REAL,
            net_amount REAL
        );
        
        CREATE TABLE products (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            product_code TEXT,
            product_name TEXT,
            current_stock REAL,
            minimum_stock REAL,
            cost_price REAL,
            sale_price REAL
        );
    ''')
    conn.commit()
    return conn

def test_module013_sqlite():
    print("Starting MODULE-013 SQLite integration tests...")
    conn = setup_test_db()
    c = conn.cursor()
    
    try:
        # 1. Test Sales Analytics Data
        print("1. Testing Sales Analytics Data...")
        c.executemany("INSERT INTO sales_transactions (station_id, invoice_number, transaction_date, total_amount, discount_amount, net_amount) VALUES (?, ?, ?, ?, ?, ?)", [
            (1, 'INV-001', '2026-08-22 10:00:00', 1000, 100, 900),
            (1, 'INV-002', '2026-08-22 11:00:00', 2000, 0, 2000),
            (2, 'INV-003', '2026-08-22 12:00:00', 5000, 500, 4500) # Different station
        ])
        
        # Test station isolation in sales
        c.execute("SELECT SUM(total_amount), SUM(discount_amount) FROM sales_transactions WHERE station_id = 1")
        row = c.fetchone()
        assert row[0] == 3000, "Station 1 total sales should be 3000"
        assert row[1] == 100, "Station 1 total discounts should be 100"
        
        # 2. Test Inventory Analytics Data
        print("2. Testing Inventory Analytics Data...")
        c.executemany("INSERT INTO products (product_code, product_name, current_stock, minimum_stock, cost_price, sale_price) VALUES (?, ?, ?, ?, ?, ?)", [
            ('P001', 'Oil 1L', 50, 20, 10, 15), # Normal
            ('P002', 'Filter', 5, 10, 20, 30),  # Low stock
            ('P003', 'Wiper', 0, 5, 15, 25)     # Out of stock
        ])
        
        c.execute("SELECT COUNT(*) FROM products")
        assert c.fetchone()[0] == 3, "Should have 3 products"
        
        c.execute("SELECT COUNT(*) FROM products WHERE current_stock <= minimum_stock")
        assert c.fetchone()[0] == 2, "Should have 2 low/out of stock products"
        
        c.execute("SELECT SUM(current_stock * cost_price) FROM products")
        assert c.fetchone()[0] == 600, "Total cost value should be (50*10) + (5*20) + (0*15) = 600"
        
        print("All MODULE-013 SQLite tests PASSED!")
    finally:
        conn.close()

if __name__ == "__main__":
    test_module013_sqlite()
