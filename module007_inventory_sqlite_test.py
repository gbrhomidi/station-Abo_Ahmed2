import sqlite3
import unittest
from datetime import datetime, timedelta, timezone


class Module007SQLiteContract(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
            CREATE TABLE stations(id INTEGER PRIMARY KEY);
            CREATE TABLE users(id INTEGER PRIMARY KEY, station_id INTEGER NOT NULL);
            CREATE TABLE products(id INTEGER PRIMARY KEY, station_id INTEGER NOT NULL, product_name TEXT, product_code TEXT, purchase_price REAL NOT NULL DEFAULT 0, minimum_stock REAL NOT NULL DEFAULT 0, is_deleted INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE warehouses(id INTEGER PRIMARY KEY, station_id INTEGER NOT NULL, warehouse_name TEXT NOT NULL, is_active INTEGER NOT NULL DEFAULT 1);
            CREATE TABLE inventory_levels(product_id INTEGER NOT NULL, warehouse_id INTEGER NOT NULL, quantity_on_hand REAL NOT NULL CHECK(quantity_on_hand >= 0), average_cost REAL NOT NULL DEFAULT 0, PRIMARY KEY(product_id, warehouse_id));
            CREATE TABLE inventory_movements(id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER NOT NULL, station_id INTEGER NOT NULL, warehouse_id INTEGER NOT NULL, movement_type TEXT NOT NULL, quantity_before REAL NOT NULL, quantity_change REAL NOT NULL, quantity_after REAL NOT NULL, total_cost REAL NOT NULL DEFAULT 0, created_at TEXT NOT NULL, is_deleted INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE stocktakes(id INTEGER PRIMARY KEY AUTOINCREMENT, warehouse_id INTEGER NOT NULL, status TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0, total_variance REAL);
            CREATE TABLE stocktake_details(id INTEGER PRIMARY KEY AUTOINCREMENT, stocktake_id INTEGER NOT NULL, product_id INTEGER NOT NULL, system_quantity REAL NOT NULL, counted_quantity REAL NOT NULL, variance_value REAL, archived INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE damaged_products(id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER NOT NULL, warehouse_id INTEGER NOT NULL, station_id INTEGER NOT NULL, quantity REAL NOT NULL, status TEXT NOT NULL DEFAULT 'pending', archived INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE stock_alerts(id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER NOT NULL, station_id INTEGER NOT NULL, alert_type TEXT NOT NULL, alert_level TEXT NOT NULL, is_resolved INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL);
        ''')
        self.db.executemany('INSERT INTO stations VALUES (?)', [(1,), (2,)])
        self.db.executemany('INSERT INTO users VALUES (?, ?)', [(101, 1), (202, 2)])
        self.db.executemany('INSERT INTO products(id, station_id, product_name, product_code, purchase_price, minimum_stock) VALUES (?, ?, ?, ?, ?, ?)', [(11, 1, 'زيت', 'OIL-1', 10, 5), (22, 2, 'زيت محطة B', 'OIL-B', 10, 5)])
        self.db.executemany('INSERT INTO warehouses VALUES (?, ?, ?, 1)', [(111, 1, 'مستودع A'), (222, 1, 'مستودع A-2'), (333, 2, 'مستودع B')])
        self.db.execute('INSERT INTO inventory_levels VALUES (11, 111, 10, 10)')
        self.db.execute('INSERT INTO inventory_levels VALUES (22, 333, 50, 10)')
        self.db.commit()

    def scope(self, station_id):
        return (station_id,)

    def movement(self, station_id, product_id, warehouse_id, kind, quantity, signed=None, fail_after=False):
        self.assertTrue(station_id > 0 and quantity > 0)
        self.db.execute('BEGIN')
        try:
            row = self.db.execute('SELECT quantity_on_hand FROM inventory_levels il JOIN warehouses w ON w.id=il.warehouse_id WHERE il.product_id=? AND il.warehouse_id=? AND w.station_id=? AND w.is_active=1', (product_id, warehouse_id, station_id)).fetchone()
            if row is None:
                before = 0.0
            else:
                before = row['quantity_on_hand']
            change = signed if kind == 'adjustment' else (quantity if kind in ('in', 'return') else -quantity)
            after = before + change
            if after < 0:
                raise ValueError('insufficient stock')
            self.db.execute('INSERT OR REPLACE INTO inventory_levels(product_id, warehouse_id, quantity_on_hand, average_cost) VALUES (?, ?, ?, COALESCE((SELECT average_cost FROM inventory_levels WHERE product_id=? AND warehouse_id=?), 10))', (product_id, warehouse_id, after, product_id, warehouse_id))
            self.db.execute('INSERT INTO inventory_movements(product_id, station_id, warehouse_id, movement_type, quantity_before, quantity_change, quantity_after, total_cost, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)', (product_id, station_id, warehouse_id, kind, before, change, after, abs(change) * 10, datetime.now(timezone.utc).isoformat()))
            if fail_after:
                raise RuntimeError('forced failure')
            self.db.commit()
        except Exception:
            self.db.rollback()
            raise

    def transfer(self, station_id, product_id, source, target, quantity):
        self.db.execute('BEGIN')
        try:
            source_row = self.db.execute('SELECT quantity_on_hand FROM inventory_levels il JOIN warehouses w ON w.id=il.warehouse_id WHERE il.product_id=? AND il.warehouse_id=? AND w.station_id=?', (product_id, source, station_id)).fetchone()
            target_row = self.db.execute('SELECT quantity_on_hand FROM inventory_levels il JOIN warehouses w ON w.id=il.warehouse_id WHERE il.product_id=? AND il.warehouse_id=? AND w.station_id=?', (product_id, target, station_id)).fetchone()
            if source_row is None or target_row is None or source_row['quantity_on_hand'] < quantity:
                raise ValueError('invalid transfer scope or balance')
            sb, tb = source_row['quantity_on_hand'], target_row['quantity_on_hand']
            self.db.execute('UPDATE inventory_levels SET quantity_on_hand=? WHERE product_id=? AND warehouse_id=?', (sb - quantity, product_id, source))
            self.db.execute('UPDATE inventory_levels SET quantity_on_hand=? WHERE product_id=? AND warehouse_id=?', (tb + quantity, product_id, target))
            now = datetime.now(timezone.utc).isoformat()
            self.db.execute('INSERT INTO inventory_movements(product_id,station_id,warehouse_id,movement_type,quantity_before,quantity_change,quantity_after,created_at) VALUES (?,?,?,?,?,?,?,?)', (product_id, station_id, source, 'transfer', sb, -quantity, sb - quantity, now))
            self.db.execute('INSERT INTO inventory_movements(product_id,station_id,warehouse_id,movement_type,quantity_before,quantity_change,quantity_after,created_at) VALUES (?,?,?,?,?,?,?,?)', (product_id, station_id, target, 'transfer', tb, quantity, tb + quantity, now))
            self.db.commit()
        except Exception:
            self.db.rollback()
            raise

    def approve_stocktake(self, stocktake_id, station_id):
        self.db.execute('BEGIN')
        try:
            take = self.db.execute('SELECT st.*, w.station_id FROM stocktakes st JOIN warehouses w ON w.id=st.warehouse_id WHERE st.id=? AND w.station_id=? AND st.archived=0', (stocktake_id, station_id)).fetchone()
            if not take or take['status'] not in ('draft', 'in_progress'):
                raise ValueError('stocktake not open')
            details = self.db.execute('SELECT * FROM stocktake_details WHERE stocktake_id=? AND archived=0', (stocktake_id,)).fetchall()
            if not details:
                raise ValueError('details required')
            total = 0.0
            for d in details:
                current = self.db.execute('SELECT quantity_on_hand FROM inventory_levels WHERE product_id=? AND warehouse_id=?', (d['product_id'], take['warehouse_id'])).fetchone()['quantity_on_hand']
                delta = d['counted_quantity'] - current
                total += delta * 10
                if delta:
                    after = current + delta
                    self.db.execute('UPDATE inventory_levels SET quantity_on_hand=? WHERE product_id=? AND warehouse_id=?', (after, d['product_id'], take['warehouse_id']))
                    self.db.execute('INSERT INTO inventory_movements(product_id,station_id,warehouse_id,movement_type,quantity_before,quantity_change,quantity_after,total_cost,created_at) VALUES (?,?,?,?,?,?,?,?,?)', (d['product_id'], station_id, take['warehouse_id'], 'adjustment', current, delta, after, abs(delta) * 10, datetime.now(timezone.utc).isoformat()))
                self.db.execute('UPDATE stocktake_details SET system_quantity=?, variance_value=? WHERE id=?', (current, delta * 10, d['id']))
            self.db.execute('UPDATE stocktakes SET status="completed", total_variance=? WHERE id=? AND status IN ("draft","in_progress")', (total, stocktake_id))
            self.db.commit()
        except Exception:
            self.db.rollback()
            raise

    def approve_damage(self, damage_id, station_id, status):
        self.db.execute('BEGIN')
        try:
            row = self.db.execute('SELECT * FROM damaged_products WHERE id=? AND station_id=? AND archived=0', (damage_id, station_id)).fetchone()
            if not row or row['status'] != 'pending':
                raise ValueError('damage is not pending')
            if status == 'approved':
                before = self.db.execute('SELECT quantity_on_hand FROM inventory_levels WHERE product_id=? AND warehouse_id=?', (row['product_id'], row['warehouse_id'])).fetchone()['quantity_on_hand']
                after = before - row['quantity']
                if after < 0: raise ValueError('insufficient stock')
                self.db.execute('UPDATE inventory_levels SET quantity_on_hand=? WHERE product_id=? AND warehouse_id=?', (after, row['product_id'], row['warehouse_id']))
                self.db.execute('INSERT INTO inventory_movements(product_id,station_id,warehouse_id,movement_type,quantity_before,quantity_change,quantity_after,total_cost,created_at) VALUES (?,?,?,?,?,?,?,?,?)', (row['product_id'], station_id, row['warehouse_id'], 'damage', before, -row['quantity'], after, row['quantity'] * 10, datetime.now(timezone.utc).isoformat()))
            self.db.execute('UPDATE damaged_products SET status=? WHERE id=? AND status="pending"', (status, damage_id))
            self.db.commit()
        except Exception:
            self.db.rollback()
            raise

    def page_movements(self, station_id, search='', movement_type='', limit=2, offset=0, start='', end=''):
        where = ['im.station_id=?', 'im.is_deleted=0']
        args = [station_id]
        if search:
            where.append('(p.product_name LIKE ? OR p.product_code LIKE ?)'); args += [f'%{search}%', f'%{search}%']
        if movement_type:
            where.append('im.movement_type=?'); args.append(movement_type)
        if start:
            where.append('date(im.created_at) >= date(?)'); args.append(start)
        if end:
            where.append('date(im.created_at) <= date(?)'); args.append(end)
        clause = ' AND '.join(where)
        total = self.db.execute(f'SELECT COUNT(*) FROM inventory_movements im JOIN products p ON p.id=im.product_id WHERE {clause}', args).fetchone()[0]
        rows = self.db.execute(f'SELECT im.* FROM inventory_movements im JOIN products p ON p.id=im.product_id WHERE {clause} ORDER BY im.id LIMIT ? OFFSET ?', args + [limit, offset]).fetchall()
        return total, rows

    def test_in_out_negative_and_atomic_rollback(self):
        self.movement(1, 11, 111, 'in', 5)
        self.assertEqual(self.db.execute('SELECT quantity_on_hand FROM inventory_levels WHERE product_id=11 AND warehouse_id=111').fetchone()[0], 15)
        with self.assertRaises(ValueError): self.movement(1, 11, 111, 'out', 99)
        before = self.db.execute('SELECT quantity_on_hand FROM inventory_levels WHERE product_id=11 AND warehouse_id=111').fetchone()[0]
        with self.assertRaises(RuntimeError): self.movement(1, 11, 111, 'out', 2, fail_after=True)
        self.assertEqual(self.db.execute('SELECT quantity_on_hand FROM inventory_levels WHERE product_id=11 AND warehouse_id=111').fetchone()[0], before)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM inventory_movements').fetchone()[0], 1)

    def test_transfer_signed_source_destination(self):
        self.db.execute('INSERT INTO inventory_levels VALUES (11,222,3,10)')
        self.db.commit()
        self.transfer(1, 11, 111, 222, 4)
        signed = [r[0] for r in self.db.execute('SELECT quantity_change FROM inventory_movements WHERE movement_type="transfer" ORDER BY id')]
        self.assertEqual(signed, [-4, 4])
        self.assertEqual(self.db.execute('SELECT quantity_on_hand FROM inventory_levels WHERE product_id=11 AND warehouse_id=111').fetchone()[0], 6)
        self.assertEqual(self.db.execute('SELECT quantity_on_hand FROM inventory_levels WHERE product_id=11 AND warehouse_id=222').fetchone()[0], 7)

    def test_stocktake_approve_adjusts_once_and_sets_variance(self):
        self.db.execute('INSERT INTO stocktakes(warehouse_id,status) VALUES (111,"draft")')
        take = self.db.execute('SELECT last_insert_rowid()').fetchone()[0]
        self.db.execute('INSERT INTO stocktake_details(stocktake_id,product_id,system_quantity,counted_quantity) VALUES (?,?,?,?)', (take,11,0,7))
        self.db.commit()
        self.approve_stocktake(take, 1)
        self.assertEqual(self.db.execute('SELECT quantity_on_hand FROM inventory_levels WHERE product_id=11 AND warehouse_id=111').fetchone()[0], 7)
        self.assertEqual(self.db.execute('SELECT status FROM stocktakes WHERE id=?',(take,)).fetchone()[0], 'completed')
        self.assertEqual(self.db.execute('SELECT quantity_change FROM inventory_movements WHERE movement_type="adjustment"').fetchone()[0], -3)
        with self.assertRaises(ValueError): self.approve_stocktake(take, 1)

    def test_damaged_approval_deducts_only_once(self):
        self.db.execute('INSERT INTO damaged_products(product_id,warehouse_id,station_id,quantity) VALUES (11,111,1,2)')
        damage = self.db.execute('SELECT last_insert_rowid()').fetchone()[0]
        self.db.commit()
        self.approve_damage(damage, 1, 'approved')
        self.assertEqual(self.db.execute('SELECT quantity_on_hand FROM inventory_levels WHERE product_id=11 AND warehouse_id=111').fetchone()[0], 8)
        with self.assertRaises(ValueError): self.approve_damage(damage, 1, 'approved')
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM inventory_movements WHERE movement_type="damage"').fetchone()[0], 1)

    def test_station_isolation_and_pagination_search_status_dates(self):
        self.movement(1, 11, 111, 'in', 1)
        self.movement(1, 11, 111, 'out', 1)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM inventory_movements WHERE station_id=?',(2,)).fetchone()[0], 0)
        self.db.execute('INSERT INTO stock_alerts(product_id,station_id,alert_type,alert_level,created_at) VALUES (11,1,"low_stock","warning",?)', ((datetime.now(timezone.utc)-timedelta(days=1)).isoformat(),))
        self.db.commit()
        total, rows = self.page_movements(1, search='OIL-1', limit=1, offset=1, start=(datetime.now(timezone.utc)-timedelta(days=2)).date().isoformat(), end=datetime.now(timezone.utc).date().isoformat())
        self.assertEqual(total, 2)
        self.assertEqual(len(rows), 1)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM stock_alerts WHERE station_id=?',(2,)).fetchone()[0], 0)


if __name__ == '__main__':
    unittest.main()
