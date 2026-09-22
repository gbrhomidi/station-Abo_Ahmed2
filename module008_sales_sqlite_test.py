import sqlite3
from datetime import datetime, timezone


def setup():
    db = sqlite3.connect(':memory:')
    db.execute('PRAGMA foreign_keys=ON')
    db.executescript('''
    CREATE TABLE stations(id INTEGER PRIMARY KEY, name TEXT);
    CREATE TABLE shifts(id INTEGER PRIMARY KEY, station_id INTEGER, status TEXT, opening_cash REAL, closing_cash REAL, total_sales REAL DEFAULT 0, total_cash REAL DEFAULT 0, total_credit_card REAL DEFAULT 0, total_bank_transfer REAL DEFAULT 0, total_credit_sales REAL DEFAULT 0, total_other REAL DEFAULT 0, cash_variance REAL DEFAULT 0, is_deleted INTEGER DEFAULT 0);
    CREATE TABLE parties(id INTEGER PRIMARY KEY, station_id INTEGER, commercial_name TEXT, is_active INTEGER DEFAULT 1, is_deleted INTEGER DEFAULT 0, current_balance REAL DEFAULT 0, total_due REAL DEFAULT 0);
    CREATE TABLE fuel_types(id INTEGER PRIMARY KEY, fuel_code TEXT, fuel_name TEXT, default_sale_price REAL, is_active INTEGER DEFAULT 1, is_deleted INTEGER DEFAULT 0);
    CREATE TABLE products(id INTEGER PRIMARY KEY, station_id INTEGER, product_name TEXT, sale_price REAL, status TEXT DEFAULT 'active', is_deleted INTEGER DEFAULT 0);
    CREATE TABLE warehouses(id INTEGER PRIMARY KEY, station_id INTEGER, is_active INTEGER DEFAULT 1, is_default INTEGER DEFAULT 0);
    CREATE TABLE inventory_levels(product_id INTEGER, warehouse_id INTEGER, quantity_on_hand REAL, PRIMARY KEY(product_id, warehouse_id));
    CREATE TABLE inventory_movements(id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER, station_id INTEGER, warehouse_id INTEGER, quantity_change REAL, movement_type TEXT, reference_id INTEGER);
    CREATE TABLE tanks(id INTEGER PRIMARY KEY, station_id INTEGER, fuel_type_id INTEGER, current_quantity REAL, status TEXT DEFAULT 'active', is_deleted INTEGER DEFAULT 0);
    CREATE TABLE pumps(id INTEGER PRIMARY KEY, station_id INTEGER, tank_id INTEGER, status TEXT DEFAULT 'active', is_deleted INTEGER DEFAULT 0);
    CREATE TABLE sales_transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, station_id INTEGER, shift_id INTEGER, customer_party_id INTEGER, fuel_type_id INTEGER, pump_id INTEGER, liters REAL, net_amount REAL, payment_method TEXT, paid_amount REAL, remaining_amount REAL, invoice_number TEXT, sale_code TEXT, status TEXT, order_type TEXT, is_deleted INTEGER DEFAULT 0, created_at TEXT);
    CREATE TABLE sale_items(id INTEGER PRIMARY KEY AUTOINCREMENT, sale_id INTEGER, line_number INTEGER, product_id INTEGER, quantity REAL, unit_price REAL, line_total REAL, item_type TEXT, returned_quantity REAL DEFAULT 0, is_returned INTEGER DEFAULT 0);
    CREATE TABLE payments(id INTEGER PRIMARY KEY AUTOINCREMENT, sale_id INTEGER, payment_type TEXT, payment_method TEXT, amount REAL, status TEXT, is_partial INTEGER, total_invoice_amount REAL, remaining_after REAL, is_deleted INTEGER DEFAULT 0);
    CREATE TABLE deliveries(id INTEGER PRIMARY KEY AUTOINCREMENT, sale_id INTEGER, party_id INTEGER, quantity REAL, fuel_type TEXT, total_amount REAL, status TEXT, delivery_date TEXT, is_deleted INTEGER DEFAULT 0);
    CREATE TABLE fuel_sales(id INTEGER PRIMARY KEY AUTOINCREMENT, sale_id INTEGER, shift_id INTEGER, pump_id INTEGER, fuel_type_id INTEGER, quantity REAL, price_per_liter REAL, total_amount REAL, payment_method TEXT, sale_date TEXT, is_deleted INTEGER DEFAULT 0);
    ''')
    db.executemany('INSERT INTO stations VALUES (?,?)', [(1,'A'),(2,'B')])
    db.executemany('INSERT INTO shifts(id,station_id,status,opening_cash) VALUES (?,?,?,?)', [(1,1,'open',100),(2,2,'open',200)])
    db.executemany('INSERT INTO parties VALUES (?,?,?,?,?,?,?)', [(1,1,'Customer A',1,0,0,0),(2,2,'Customer B',1,0,0,0)])
    db.execute('INSERT INTO fuel_types VALUES (1,"DIESEL","Diesel",1.5,1,0)')
    db.executemany('INSERT INTO products VALUES (?,?,?,?,?,?)', [(1,1,'Oil',10,'active',0),(2,2,'Oil B',10,'active',0)])
    db.executemany('INSERT INTO warehouses VALUES (?,?,?,?)', [(1,1,1,1),(2,2,1,1)])
    db.execute('INSERT INTO inventory_levels VALUES (1,1,5)')
    db.execute('INSERT INTO tanks VALUES (1,1,1,100,"active",0)')
    db.execute('INSERT INTO pumps VALUES (1,1,1,"active",0)')
    db.commit()
    return db


def product_sale(db, station, shift, cashier_customer, quantities, paid=None):
    total = sum(q * 10 for _, q in quantities)
    paid = total if paid is None else paid
    db.execute('BEGIN')
    try:
        assert db.execute('SELECT 1 FROM shifts WHERE id=? AND station_id=? AND status="open" AND is_deleted=0', (shift, station)).fetchone()
        for product_id, qty in quantities:
            assert qty > 0
            row = db.execute('SELECT sale_price FROM products WHERE id=? AND station_id=? AND status="active" AND is_deleted=0', (product_id, station)).fetchone()
            assert row
            stock = db.execute('SELECT quantity_on_hand FROM inventory_levels il JOIN warehouses w ON w.id=il.warehouse_id WHERE il.product_id=? AND w.station_id=?', (product_id, station)).fetchone()
            assert stock and stock[0] >= qty, 'insufficient stock'
        cur = db.execute('INSERT INTO sales_transactions(station_id,shift_id,customer_party_id,liters,net_amount,payment_method,paid_amount,remaining_amount,invoice_number,sale_code,status,order_type,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)', (station,shift,cashier_customer,0,total,'cash',paid,max(total-paid,0),'INV-T','SALE-T','completed','product',datetime.now(timezone.utc).isoformat()))
        sale_id = cur.lastrowid
        db.execute('INSERT INTO payments(sale_id,payment_type,payment_method,amount,status,is_partial,total_invoice_amount,remaining_after) VALUES (?,?,?,?,?,?,?,?)', (sale_id,'cash','cash',paid,'completed',int(paid<total),total,max(total-paid,0)))
        for idx, (product_id, qty) in enumerate(quantities, 1):
            db.execute('INSERT INTO sale_items(sale_id,line_number,product_id,quantity,unit_price,line_total,item_type) VALUES (?,?,?,?,?,?,?)', (sale_id,idx,product_id,qty,10,qty*10,'product'))
            before = db.execute('SELECT quantity_on_hand FROM inventory_levels WHERE product_id=? AND warehouse_id=1', (product_id,)).fetchone()[0]
            after = before - qty
            db.execute('UPDATE inventory_levels SET quantity_on_hand=? WHERE product_id=? AND warehouse_id=1 AND quantity_on_hand>=?', (after,product_id,qty))
            assert db.total_changes >= 1
            db.execute('INSERT INTO inventory_movements(product_id,station_id,warehouse_id,quantity_change,movement_type,reference_id) VALUES (?,?,?,?,?,?)', (product_id,station,1,-qty,'out',sale_id))
        db.execute('UPDATE shifts SET total_sales=total_sales+?,total_cash=total_cash+? WHERE id=? AND station_id=? AND status="open"', (total,total,shift,station))
        db.execute('COMMIT')
        return sale_id
    except Exception:
        db.execute('ROLLBACK')
        raise


def fuel_sale(db, station, shift, liters):
    db.execute('BEGIN')
    try:
        tank = db.execute('SELECT t.id,t.current_quantity FROM tanks t JOIN pumps p ON p.tank_id=t.id WHERE p.id=1 AND p.station_id=? AND p.status="active" AND t.station_id=? AND t.status="active"', (station,station)).fetchone()
        assert tank and tank[1] >= liters, 'insufficient fuel'
        cur = db.execute('INSERT INTO sales_transactions(station_id,shift_id,fuel_type_id,pump_id,liters,net_amount,payment_method,paid_amount,remaining_amount,invoice_number,sale_code,status,order_type,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)', (station,shift,1,1,liters,liters*1.5,'cash',liters*1.5,0,'INV-F','SALE-F','completed','fuel',datetime.now(timezone.utc).isoformat()))
        sale_id=cur.lastrowid
        changed=db.execute('UPDATE tanks SET current_quantity=current_quantity-? WHERE id=? AND station_id=? AND current_quantity>=?', (liters,tank[0],station,liters)).rowcount
        assert changed == 1
        db.execute('INSERT INTO fuel_sales(sale_id,shift_id,pump_id,fuel_type_id,quantity,price_per_liter,total_amount,payment_method,sale_date) VALUES (?,?,?,?,?,?,?,?,date("now"))', (sale_id,shift,1,1,liters,1.5,liters*1.5,'cash'))
        db.execute('COMMIT')
    except Exception:
        db.execute('ROLLBACK')
        raise


def test_module008():
    db=setup()
    product_sale(db,1,1,1,[(1,2)])
    assert db.execute('SELECT quantity_on_hand FROM inventory_levels WHERE product_id=1 AND warehouse_id=1').fetchone()[0] == 3
    assert db.execute('SELECT quantity_change FROM inventory_movements WHERE reference_id=1').fetchone()[0] == -2
    try: product_sale(db,1,1,1,[(1,4)])
    except AssertionError: pass
    else: raise AssertionError('oversell was accepted')
    assert db.execute('SELECT COUNT(*) FROM sales_transactions').fetchone()[0] == 1
    fuel_sale(db,1,1,10)
    assert db.execute('SELECT current_quantity FROM tanks WHERE id=1').fetchone()[0] == 90
    try: fuel_sale(db,1,1,1000)
    except AssertionError: pass
    else: raise AssertionError('fuel oversell was accepted')
    assert db.execute('SELECT COUNT(*) FROM fuel_sales').fetchone()[0] == 1
    db.execute('INSERT INTO sales_transactions(station_id,shift_id,net_amount,payment_method,paid_amount,remaining_amount,invoice_number,sale_code,status,order_type,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)', (2,2,99,'cash',99,0,'INV-B','SALE-B','completed','product',datetime.now(timezone.utc).isoformat()))
    assert db.execute('SELECT COUNT(*) FROM sales_transactions WHERE station_id=1').fetchone()[0] == 2
    assert db.execute('SELECT COUNT(*) FROM sales_transactions WHERE station_id=1 AND station_id=2').fetchone()[0] == 0
    page = db.execute('SELECT id FROM sales_transactions WHERE station_id=? AND is_deleted=0 ORDER BY id DESC LIMIT ? OFFSET ?', (1,1,0)).fetchall()
    assert len(page)==1
    assert db.execute('SELECT COUNT(*) FROM sales_transactions WHERE station_id=? AND payment_method=?', (1,'cash')).fetchone()[0]==2
    print('MODULE-008 SQLite integration test passed')


if __name__ == '__main__':
    test_module008()
