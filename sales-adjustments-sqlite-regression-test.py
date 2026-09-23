import sqlite3

db = sqlite3.connect(":memory:")
db.executescript("""
CREATE TABLE sales_transactions(id INTEGER PRIMARY KEY, station_id INTEGER, net_amount REAL, liters REAL, paid_amount REAL, remaining_amount REAL, status TEXT);
CREATE TABLE sale_items(id INTEGER PRIMARY KEY, sale_id INTEGER, product_id INTEGER, quantity REAL, returned_quantity REAL DEFAULT 0, damaged_quantity REAL DEFAULT 0, line_total REAL);
CREATE TABLE fuel_sales(id INTEGER PRIMARY KEY, sale_id INTEGER, quantity REAL, total_amount REAL);
CREATE TABLE fuel_sale_adjustments(id INTEGER PRIMARY KEY, sale_id INTEGER, adjustment_type TEXT, quantity REAL, amount REAL, status TEXT);
CREATE TABLE inventory_levels(product_id INTEGER PRIMARY KEY, quantity_on_hand REAL);
""")
db.execute("INSERT INTO sales_transactions VALUES(1,1,100,20,100,0,'completed')")
db.execute("INSERT INTO sale_items VALUES(1,1,10,5,0,0,50)")
db.execute("INSERT INTO fuel_sales VALUES(1,1,20,100)")
db.execute("INSERT INTO inventory_levels VALUES(10,95)")

db.execute("UPDATE sale_items SET returned_quantity=returned_quantity+2 WHERE id=1 AND quantity >= returned_quantity+damaged_quantity+2")
db.execute("UPDATE inventory_levels SET quantity_on_hand=quantity_on_hand+2 WHERE product_id=10")
assert db.execute("SELECT returned_quantity FROM sale_items WHERE id=1").fetchone()[0] == 2
assert db.execute("SELECT quantity_on_hand FROM inventory_levels WHERE product_id=10").fetchone()[0] == 97

db.execute("UPDATE sale_items SET damaged_quantity=damaged_quantity+1 WHERE id=1 AND quantity >= returned_quantity+damaged_quantity+1")
assert db.execute("SELECT quantity_on_hand FROM inventory_levels WHERE product_id=10").fetchone()[0] == 97

db.execute("INSERT INTO fuel_sale_adjustments VALUES(1,1,'return',3,15,'posted')")
db.execute("INSERT INTO fuel_sale_adjustments VALUES(2,1,'damage',2,10,'posted')")
net = db.execute("""SELECT MAX(0,fs.quantity-COALESCE(a.q,0)), MAX(0,fs.total_amount-COALESCE(a.a,0))
                    FROM fuel_sales fs LEFT JOIN
                    (SELECT sale_id,SUM(quantity) q,SUM(amount) a FROM fuel_sale_adjustments WHERE status='posted' GROUP BY sale_id) a
                    ON a.sale_id=fs.sale_id WHERE fs.sale_id=1""").fetchone()
assert net == (15, 75)

db.executescript("""
CREATE TABLE idempotency(station_id INTEGER, operation TEXT, idempotency_key TEXT, UNIQUE(station_id,operation,idempotency_key));
""")
db.execute("INSERT INTO idempotency VALUES(1,'fuel_sale_adjustment','k1')")
try:
    db.execute("INSERT INTO idempotency VALUES(1,'fuel_sale_adjustment','k1')")
    raise AssertionError("duplicate idempotency key was accepted")
except sqlite3.IntegrityError:
    pass

print("Sales/inventory SQLite regression test PASS")
