import sqlite3
from datetime import datetime


def setup():
    db = sqlite3.connect(':memory:')
    db.execute('PRAGMA foreign_keys=ON')
    db.executescript('''
      CREATE TABLE stations(id INTEGER PRIMARY KEY, name TEXT);
      CREATE TABLE users(id INTEGER PRIMARY KEY, username TEXT);
      CREATE TABLE employees(
        id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT UNIQUE, employee_code TEXT UNIQUE NOT NULL,
        full_name TEXT NOT NULL, phone TEXT, department TEXT, job_title TEXT NOT NULL,
        employment_type TEXT DEFAULT 'full_time', hire_date TEXT NOT NULL, station_id INTEGER NOT NULL,
        basic_salary REAL DEFAULT 0, housing_allowance REAL DEFAULT 0, transport_allowance REAL DEFAULT 0,
        food_allowance REAL DEFAULT 0, other_allowances REAL DEFAULT 0, total_salary REAL DEFAULT 0,
        insurance_deduction REAL DEFAULT 0, tax_deduction REAL DEFAULT 0, other_deductions REAL DEFAULT 0,
        status TEXT DEFAULT 'active', is_deleted INTEGER DEFAULT 0
      );
      CREATE TABLE attendance(
        id INTEGER PRIMARY KEY AUTOINCREMENT, employee_id INTEGER NOT NULL, station_id INTEGER NOT NULL,
        attendance_date TEXT NOT NULL, check_in TEXT, check_out TEXT, work_hours REAL,
        overtime_hours REAL DEFAULT 0, status TEXT DEFAULT 'present', is_deleted INTEGER DEFAULT 0
      );
      CREATE TABLE payroll(
        id INTEGER PRIMARY KEY AUTOINCREMENT, station_id INTEGER NOT NULL, payroll_code TEXT UNIQUE,
        period_start TEXT NOT NULL, period_end TEXT NOT NULL, total_net_salary REAL DEFAULT 0,
        status TEXT DEFAULT 'calculated', is_deleted INTEGER DEFAULT 0
      );
      CREATE TABLE payroll_items(
        id INTEGER PRIMARY KEY AUTOINCREMENT, payroll_id INTEGER, employee_id INTEGER,
        net_salary REAL, paid_amount REAL DEFAULT 0, payment_status TEXT DEFAULT 'pending'
      );
      CREATE TABLE cash_boxes(id INTEGER PRIMARY KEY, station_id INTEGER, current_balance REAL, status TEXT, is_deleted INTEGER DEFAULT 0);
      CREATE TABLE employee_payments(
        id INTEGER PRIMARY KEY AUTOINCREMENT, employee_id INTEGER, station_id INTEGER, payroll_id INTEGER,
        amount REAL, type TEXT CHECK(type IN ('salary','advance','penalty','bonus','other','deduction','allowance')), status TEXT, is_deleted INTEGER DEFAULT 0
      );
    ''')
    db.executemany('INSERT INTO stations VALUES (?,?)', [(1, 'A'), (2, 'B')])
    db.executemany('INSERT INTO users VALUES (?,?)', [(10, 'a-user'), (20, 'b-user')])
    db.executemany('INSERT INTO employees(uuid,employee_code,full_name,phone,department,job_title,hire_date,station_id,basic_salary,housing_allowance,insurance_deduction,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)', [
      ('e-a-1','A-001','Employee A','0500000001','Operations','Operator','2026-01-01',1,1000,100,50,'active'),
      ('e-a-2','A-002','Employee A2','0500000002','Operations','Operator','2026-01-01',1,1200,0,0,'active'),
      ('e-b-1','B-001','Employee B','0500000003','Operations','Operator','2026-01-01',2,900,0,0,'active'),
    ])
    db.execute('INSERT INTO cash_boxes VALUES (1,1,2000,"active",0)')
    db.commit()
    return db


def check_in(db, station, employee, date, when):
    assert db.execute('SELECT 1 FROM employees WHERE id=? AND station_id=? AND status="active" AND is_deleted=0', (employee, station)).fetchone()
    assert not db.execute('SELECT 1 FROM attendance WHERE employee_id=? AND station_id=? AND attendance_date=? AND check_out IS NULL AND is_deleted=0', (employee, station, date)).fetchone()
    cur = db.execute('INSERT INTO attendance(employee_id,station_id,attendance_date,check_in,status) VALUES (?,?,?,?,?)', (employee, station, date, when, 'present'))
    return cur.lastrowid


def check_out(db, station, attendance_id, when):
    row = db.execute('SELECT check_in FROM attendance WHERE id=? AND station_id=? AND check_out IS NULL', (attendance_id, station)).fetchone()
    assert row
    start = datetime.strptime(row[0], '%Y-%m-%d %H:%M:%S')
    end = datetime.strptime(when, '%Y-%m-%d %H:%M:%S')
    assert end > start
    hours = (end-start).total_seconds()/3600
    db.execute('UPDATE attendance SET check_out=?, work_hours=?, overtime_hours=? WHERE id=? AND station_id=? AND check_out IS NULL', (when, hours, max(0,hours-8), attendance_id, station))


def create_payroll(db, station, start, end):
    assert not db.execute('SELECT 1 FROM payroll WHERE station_id=? AND period_start=? AND period_end=? AND is_deleted=0', (station,start,end)).fetchone()
    employees = db.execute('SELECT id,basic_salary,housing_allowance,transport_allowance,food_allowances,other_allowances FROM employees WHERE station_id=?', (station,)).fetchall() if False else db.execute('SELECT id,basic_salary,housing_allowance,transport_allowance,food_allowance,other_allowances,insurance_deduction,tax_deduction,other_deductions FROM employees WHERE station_id=? AND status="active" AND is_deleted=0', (station,)).fetchall()
    total=0
    items=[]
    for e in employees:
        earnings=e[1]+e[2]+e[3]+e[4]+e[5]
        deductions=e[6]+e[7]+e[8]
        net=earnings-deductions
        assert net >= 0
        items.append((e[0],net)); total+=net
    cur=db.execute('INSERT INTO payroll(station_id,payroll_code,period_start,period_end,total_net_salary) VALUES (?,?,?,?,?)', (station, 'P-'+str(station)+'-'+start, start,end,total))
    pid=cur.lastrowid
    db.executemany('INSERT INTO payroll_items(payroll_id,employee_id,net_salary) VALUES (?,?,?)', [(pid,e,n) for e,n in items])
    db.commit()
    return pid


def pay(db, station, employee, payroll, amount):
    item=db.execute('SELECT pi.id,pi.net_salary,pi.paid_amount FROM payroll_items pi JOIN payroll p ON p.id=pi.payroll_id WHERE pi.payroll_id=? AND pi.employee_id=? AND p.station_id=? AND p.is_deleted=0', (payroll,employee,station)).fetchone()
    assert item and amount <= item[1]-item[2]+1e-9
    cash=db.execute('SELECT current_balance FROM cash_boxes WHERE id=1 AND station_id=? AND status="active"', (station,)).fetchone()
    assert cash and cash[0] >= amount
    db.execute('BEGIN')
    try:
        db.execute('UPDATE cash_boxes SET current_balance=current_balance-? WHERE id=1', (amount,))
        db.execute('INSERT INTO employee_payments(employee_id,station_id,payroll_id,amount,type,status) VALUES (?,?,?,?,?,?)', (employee,station,payroll,amount,'salary','completed'))
        db.execute('UPDATE payroll_items SET paid_amount=paid_amount+?,payment_status=CASE WHEN paid_amount+? >= net_salary THEN "paid" ELSE "pending" END WHERE id=?', (amount,amount,item[0]))
        db.commit()
    except Exception:
        db.rollback(); raise


def test_employee_payment_edge_cases(db, station, employee):
    db.execute('INSERT INTO employee_payments(employee_id,station_id,amount,type,status) VALUES (?,?,?,?,?)', (employee, station, 25.0, 'deduction', 'pending'))
    db.execute('INSERT INTO employee_payments(employee_id,station_id,amount,type,status) VALUES (?,?,?,?,?)', (employee, station, 10.0, 'allowance', 'paid'))
    db.commit()
    rows = db.execute('SELECT type,amount FROM employee_payments WHERE station_id=? AND is_deleted=0', (station,)).fetchall()
    assert ('deduction', 25.0) in rows
    assert ('allowance', 10.0) in rows
    try:
        db.execute('INSERT INTO employee_payments(employee_id,station_id,amount,type,status) VALUES (?,?,?,?,?)', (employee, station, 1.0, 'invalid', 'pending'))
    except sqlite3.IntegrityError:
        db.rollback()
    else:
        raise AssertionError('invalid employee payment type accepted')
    db.execute('UPDATE employee_payments SET is_deleted=1 WHERE employee_id=? AND station_id=? AND type=?', (employee, station, 'deduction'))
    db.commit()
    assert db.execute('SELECT COUNT(*) FROM employee_payments WHERE type=? AND station_id=? AND is_deleted=0', ('deduction', station)).fetchone()[0] == 0
    assert db.execute('SELECT COUNT(*) FROM employee_payments WHERE type=? AND station_id=? AND is_deleted=0', ('allowance', station)).fetchone()[0] == 1
    assert db.execute('SELECT COUNT(*) FROM employee_payments WHERE type=? AND station_id=? AND is_deleted=0', ('allowance', 2)).fetchone()[0] == 0


def test_module010_hr():
    db=setup()
    a1=db.execute('SELECT id FROM employees WHERE employee_code="A-001"').fetchone()[0]
    b1=db.execute('SELECT id FROM employees WHERE employee_code="B-001"').fetchone()[0]
    att=check_in(db,1,a1,'2026-08-22','2026-08-22 08:00:00')
    try: check_in(db,1,a1,'2026-08-22','2026-08-22 09:00:00')
    except AssertionError: pass
    else: raise AssertionError('duplicate open attendance accepted')
    try: check_out(db,1,att,'2026-08-22 07:00:00')
    except AssertionError: pass
    else: raise AssertionError('checkout before checkin accepted')
    check_out(db,1,att,'2026-08-22 17:30:00')
    assert round(db.execute('SELECT work_hours FROM attendance WHERE id=?',(att,)).fetchone()[0],2)==9.5
    pid=create_payroll(db,1,'2026-08-01','2026-08-31')
    net=db.execute('SELECT net_salary FROM payroll_items WHERE payroll_id=? AND employee_id=?',(pid,a1)).fetchone()[0]
    pay(db,1,a1,pid,net/2)
    assert db.execute('SELECT payment_status FROM payroll_items WHERE payroll_id=? AND employee_id=?',(pid,a1)).fetchone()[0]=='pending'
    pay(db,1,a1,pid,net/2)
    assert db.execute('SELECT payment_status FROM payroll_items WHERE payroll_id=? AND employee_id=?',(pid,a1)).fetchone()[0]=='paid'
    try: pay(db,1,a1,pid,1)
    except AssertionError: pass
    else: raise AssertionError('overpayment accepted')
    assert db.execute('SELECT COUNT(*) FROM employees WHERE station_id=1').fetchone()[0]==2
    assert db.execute('SELECT COUNT(*) FROM employees WHERE station_id=2').fetchone()[0]==1
    assert db.execute('SELECT COUNT(*) FROM attendance WHERE station_id=2').fetchone()[0]==0
    assert db.execute('SELECT COUNT(*) FROM payroll WHERE station_id=2').fetchone()[0]==0
    assert db.execute('SELECT current_balance FROM cash_boxes WHERE id=1').fetchone()[0] == 950.0
    test_employee_payment_edge_cases(db, 1, a1)
    print('MODULE-010 SQLite HR test passed with employee-payment edge cases')

if __name__=='__main__': test_module010_hr()
