import sqlite3
from datetime import datetime, timezone

def setup():
    db = sqlite3.connect(':memory:')
    db.execute('PRAGMA foreign_keys=ON')
    db.executescript('''
    CREATE TABLE stations(id INTEGER PRIMARY KEY, name TEXT);
    CREATE TABLE parties(id INTEGER PRIMARY KEY, station_id INTEGER, commercial_name TEXT, is_deleted INTEGER DEFAULT 0);
    CREATE TABLE cash_boxes(id INTEGER PRIMARY KEY, station_id INTEGER, current_balance REAL, is_deleted INTEGER DEFAULT 0, status TEXT DEFAULT 'active');
    CREATE TABLE bank_accounts(id INTEGER PRIMARY KEY, station_id INTEGER, current_balance REAL, is_deleted INTEGER DEFAULT 0, status TEXT DEFAULT 'active');
    CREATE TABLE payments(id INTEGER PRIMARY KEY AUTOINCREMENT, payment_code TEXT, amount REAL, customer_party_id INTEGER, cash_box_id INTEGER, status TEXT, is_deleted INTEGER DEFAULT 0);
    CREATE TABLE cash_movements(id INTEGER PRIMARY KEY AUTOINCREMENT, cash_box_id INTEGER, movement_type TEXT, amount REAL, balance_before REAL, balance_after REAL, description TEXT, is_deleted INTEGER DEFAULT 0);
    CREATE TABLE cash_deposits(id INTEGER PRIMARY KEY AUTOINCREMENT, deposit_code TEXT, station_id INTEGER, cash_box_id INTEGER, bank_account_id INTEGER, amount REAL, description TEXT, status TEXT, is_deleted INTEGER DEFAULT 0);
    ''')
    db.executemany('INSERT INTO stations VALUES (?,?)', [(1,'A')])
    db.executemany('INSERT INTO parties VALUES (?,?,?,?)', [(1,1,'Customer A',0)])
    db.executemany('INSERT INTO cash_boxes VALUES (?,?,?,?,?)', [(1,1,1000.0,0,'active')])
    db.executemany('INSERT INTO bank_accounts VALUES (?,?,?,?,?)', [(1,1,5000.0,0,'active')])
    db.commit()
    return db

def add_payment(db, station, amount, cashbox, customer):
    db.execute('BEGIN')
    try:
        cb = db.execute('SELECT current_balance FROM cash_boxes WHERE id=? AND station_id=? AND status="active"', (cashbox, station)).fetchone()
        assert cb and cb[0] >= amount, 'insufficient cashbox balance'
        db.execute('UPDATE cash_boxes SET current_balance=current_balance-? WHERE id=?', (amount, cashbox))
        db.execute('INSERT INTO cash_movements(cash_box_id,movement_type,amount,balance_before,balance_after) VALUES (?,?,?,?,?)', (cashbox,'out',amount,cb[0],cb[0]-amount))
        db.execute('INSERT INTO payments(payment_code,amount,customer_party_id,cash_box_id,status) VALUES (?,?,?,?,?)', ('PAY-1',amount,customer,cashbox,'completed'))
        db.execute('COMMIT')
    except Exception:
        db.execute('ROLLBACK')
        raise

def add_deposit(db, station, amount, cashbox, bank):
    db.execute('BEGIN')
    try:
        cb = db.execute('SELECT current_balance FROM cash_boxes WHERE id=? AND station_id=? AND status="active"', (cashbox, station)).fetchone()
        assert cb and cb[0] >= amount, 'insufficient cashbox balance'
        ba = db.execute('SELECT current_balance FROM bank_accounts WHERE id=? AND station_id=? AND status="active"', (bank, station)).fetchone()
        assert ba
        db.execute('UPDATE cash_boxes SET current_balance=current_balance-? WHERE id=?', (amount, cashbox))
        db.execute('UPDATE bank_accounts SET current_balance=current_balance+? WHERE id=?', (amount, bank))
        db.execute('INSERT INTO cash_movements(cash_box_id,movement_type,amount,balance_before,balance_after) VALUES (?,?,?,?,?)', (cashbox,'out',amount,cb[0],cb[0]-amount))
        db.execute('INSERT INTO cash_deposits(deposit_code,station_id,cash_box_id,bank_account_id,amount,status) VALUES (?,?,?,?,?,?)', ('DEP-1',station,cashbox,bank,amount,'completed'))
        db.execute('COMMIT')
    except Exception:
        db.execute('ROLLBACK')
        raise

def test_module009_finance():
    db = setup()
    add_payment(db, 1, 200, 1, 1)
    assert db.execute('SELECT current_balance FROM cash_boxes WHERE id=1').fetchone()[0] == 800.0
    assert db.execute('SELECT COUNT(*) FROM payments').fetchone()[0] == 1
    assert db.execute('SELECT COUNT(*) FROM cash_movements').fetchone()[0] == 1
    try: add_payment(db, 1, 9000, 1, 1)
    except AssertionError: pass
    else: raise AssertionError('oversell cash accepted')
    add_deposit(db, 1, 300, 1, 1)
    assert db.execute('SELECT current_balance FROM cash_boxes WHERE id=1').fetchone()[0] == 500.0
    assert db.execute('SELECT current_balance FROM bank_accounts WHERE id=1').fetchone()[0] == 5300.0
    assert db.execute('SELECT COUNT(*) FROM cash_deposits').fetchone()[0] == 1
    assert db.execute('SELECT COUNT(*) FROM cash_movements').fetchone()[0] == 2
    print('MODULE-009 SQLite finance test passed')

if __name__ == '__main__':
    test_module009_finance()
