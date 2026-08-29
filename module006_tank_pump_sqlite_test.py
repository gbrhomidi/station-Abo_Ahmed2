import sqlite3
from datetime import datetime


def db():
    con = sqlite3.connect(':memory:')
    con.execute('PRAGMA foreign_keys=ON')
    con.executescript('''
      CREATE TABLE stations(id INTEGER PRIMARY KEY, name TEXT);
      CREATE TABLE fuel_types(id INTEGER PRIMARY KEY, fuel_code TEXT, fuel_name TEXT, is_deleted INTEGER DEFAULT 0);
      CREATE TABLE tanks(
        id INTEGER PRIMARY KEY, tank_code TEXT UNIQUE, station_id INTEGER NOT NULL, fuel_type_id INTEGER NOT NULL,
        capacity_liters REAL NOT NULL, current_quantity REAL NOT NULL DEFAULT 0, is_deleted INTEGER DEFAULT 0,
        FOREIGN KEY(station_id) REFERENCES stations(id), FOREIGN KEY(fuel_type_id) REFERENCES fuel_types(id)
      );
      CREATE TABLE pumps(
        id INTEGER PRIMARY KEY, pump_code TEXT UNIQUE, station_id INTEGER NOT NULL, tank_id INTEGER NOT NULL,
        meter_start REAL DEFAULT 0, meter_current REAL DEFAULT 0, is_deleted INTEGER DEFAULT 0,
        FOREIGN KEY(station_id) REFERENCES stations(id), FOREIGN KEY(tank_id) REFERENCES tanks(id)
      );
      CREATE TABLE pump_nozzles(
        id INTEGER PRIMARY KEY, nozzle_code TEXT UNIQUE, nozzle_number TEXT, pump_id INTEGER NOT NULL,
        fuel_type_id INTEGER NOT NULL, is_deleted INTEGER DEFAULT 0,
        FOREIGN KEY(pump_id) REFERENCES pumps(id), FOREIGN KEY(fuel_type_id) REFERENCES fuel_types(id)
      );
      CREATE TABLE tank_refills(
        id INTEGER PRIMARY KEY, refill_code TEXT UNIQUE, tank_id INTEGER NOT NULL, station_id INTEGER NOT NULL,
        fuel_type_id INTEGER NOT NULL, delivered_quantity REAL NOT NULL, actual_quantity REAL NOT NULL,
        tank_level_before REAL NOT NULL, tank_level_after REAL NOT NULL, status TEXT NOT NULL DEFAULT 'completed',
        arrival_date TEXT, is_deleted INTEGER DEFAULT 0,
        FOREIGN KEY(tank_id) REFERENCES tanks(id)
      );
      CREATE TABLE meter_readings(
        id INTEGER PRIMARY KEY, reading_code TEXT UNIQUE, pump_id INTEGER NOT NULL, nozzle_id INTEGER NOT NULL,
        station_id INTEGER NOT NULL, reading_date TEXT NOT NULL, opening_reading REAL NOT NULL,
        closing_reading REAL NOT NULL, sold_liters REAL NOT NULL, status TEXT DEFAULT 'draft', is_deleted INTEGER DEFAULT 0
      );
      CREATE TABLE fuel_quality_tests(
        id INTEGER PRIMARY KEY, refill_id INTEGER NOT NULL, test_date TEXT, density REAL, result TEXT,
        FOREIGN KEY(refill_id) REFERENCES tank_refills(id)
      );
      CREATE TABLE calibration_records(
        id INTEGER PRIMARY KEY, calibration_code TEXT UNIQUE, entity_type TEXT NOT NULL, entity_id INTEGER NOT NULL,
        station_id INTEGER NOT NULL, calibration_date TEXT NOT NULL, status TEXT DEFAULT 'completed'
      );
      CREATE INDEX idx_tanks_station_deleted ON tanks(station_id, is_deleted);
      CREATE INDEX idx_refills_station_date ON tank_refills(station_id, arrival_date, is_deleted);
      CREATE INDEX idx_pumps_station_tank ON pumps(station_id, tank_id, is_deleted);
      CREATE INDEX idx_meter_station_date ON meter_readings(station_id, reading_date, is_deleted);
      CREATE INDEX idx_quality_refill_date ON fuel_quality_tests(refill_id, test_date);
      CREATE INDEX idx_calibration_station_next ON calibration_records(station_id, calibration_date);
    ''')
    con.executemany('INSERT INTO stations VALUES (?,?)', [(1, 'A'), (2, 'B')])
    con.executemany('INSERT INTO fuel_types(id,fuel_code,fuel_name) VALUES (?,?,?)', [(1,'DIESEL','Diesel'), (2,'GAS','Gasoline')])
    con.executemany('INSERT INTO tanks(id,tank_code,station_id,fuel_type_id,capacity_liters,current_quantity) VALUES (?,?,?,?,?,?)', [
        (11,'A-T1',1,1,1000,100), (12,'B-T1',2,1,1000,100), (13,'A-T2',1,2,500,50)])
    con.executemany('INSERT INTO pumps(id,pump_code,station_id,tank_id,meter_start,meter_current) VALUES (?,?,?,?,?,?)', [
        (21,'A-P1',1,11,0,100), (22,'B-P1',2,12,0,200)])
    con.executemany('INSERT INTO pump_nozzles(id,nozzle_code,nozzle_number,pump_id,fuel_type_id) VALUES (?,?,?,?,?)', [
        (31,'A-N1','1',21,1), (32,'B-N1','1',22,1)])
    return con


def refill(con, station_id, tank_id, fuel_type_id, delivered, actual=None, code='R'):
    actual = delivered if actual is None else actual
    tank = con.execute('SELECT current_quantity, capacity_liters, fuel_type_id FROM tanks WHERE id=? AND station_id=? AND is_deleted=0', (tank_id, station_id)).fetchone()
    assert tank is not None, 'tank must belong to station'
    assert tank[2] == fuel_type_id, 'fuel type must match tank'
    assert delivered > 0 and actual > 0
    before, capacity = tank[0], tank[1]
    after = before + actual
    assert after <= capacity, 'refill must not exceed capacity'
    with con:
        rid = con.execute('INSERT INTO tank_refills(refill_code,tank_id,station_id,fuel_type_id,delivered_quantity,actual_quantity,tank_level_before,tank_level_after,arrival_date) VALUES (?,?,?,?,?,?,?,?,?)', (code,tank_id,station_id,fuel_type_id,delivered,actual,before,after,'2026-08-22')).lastrowid
        changed = con.execute('UPDATE tanks SET current_quantity=? WHERE id=? AND station_id=? AND is_deleted=0', (after,tank_id,station_id)).rowcount
        assert changed == 1
    return rid


def page(con, table, station_id, search='', limit=2, offset=0, sort='id', direction='ASC'):
    allow = {'id','reading_date','arrival_date','calibration_date','status'}
    assert sort in allow
    direction = 'DESC' if direction.upper() == 'DESC' else 'ASC'
    if table == 'tanks':
        where = 'station_id=? AND is_deleted=0'
        args = [station_id]
        if search:
            where += ' AND (tank_code LIKE ? OR id LIKE ?)'
            args += [f'%{search}%', f'%{search}%']
    elif table == 'tank_refills':
        where = 'station_id=? AND is_deleted=0'
        args = [station_id]
    elif table == 'meter_readings':
        where = 'station_id=? AND is_deleted=0'
        args = [station_id]
    else:
        raise AssertionError(table)
    total = con.execute(f'SELECT COUNT(*) FROM {table} WHERE {where}', args).fetchone()[0]
    rows = con.execute(f'SELECT * FROM {table} WHERE {where} ORDER BY {sort} {direction} LIMIT ? OFFSET ?', args + [limit, offset]).fetchall()
    return total, rows


def main():
    con = db()
    # Station isolation for direct and relational records.
    assert [r[0] for r in con.execute('SELECT id FROM tanks WHERE station_id=? AND is_deleted=0', (1,))] == [11, 13]
    assert [r[0] for r in con.execute('SELECT p.id FROM pumps p JOIN tanks t ON t.id=p.tank_id WHERE p.station_id=? AND t.station_id=? AND p.is_deleted=0 AND t.is_deleted=0', (1,1))] == [21]
    assert con.execute('SELECT COUNT(*) FROM pump_nozzles n JOIN pumps p ON p.id=n.pump_id WHERE p.station_id=? AND n.is_deleted=0 AND p.is_deleted=0', (1,)).fetchone()[0] == 1
    # Cross-station relation must fail before write.
    try:
        refill(con, 1, 12, 1, 10, code='BAD-CROSS')
        raise AssertionError('cross-station refill was accepted')
    except AssertionError as exc:
        assert 'tank must belong' in str(exc)
    # Atomic refill updates both tables and persists the computed levels.
    rid = refill(con, 1, 11, 1, 50, 48, 'A-R1')
    row = con.execute('SELECT tank_level_before,tank_level_after,actual_quantity FROM tank_refills WHERE id=?', (rid,)).fetchone()
    assert row == (100.0, 148.0, 48.0)
    assert con.execute('SELECT current_quantity FROM tanks WHERE id=11').fetchone()[0] == 148.0
    # Capacity and mismatch validation.
    try:
        refill(con, 1, 11, 1, 900, 900, 'A-OVER')
        raise AssertionError('capacity overflow was accepted')
    except AssertionError as exc:
        assert 'capacity' in str(exc)
    try:
        refill(con, 1, 13, 1, 10, code='A-WRONG-FUEL')
        raise AssertionError('fuel mismatch was accepted')
    except AssertionError as exc:
        assert 'fuel type' in str(exc)
    # Meter reading relation and numeric invariant.
    con.execute('INSERT INTO meter_readings(reading_code,pump_id,nozzle_id,station_id,reading_date,opening_reading,closing_reading,sold_liters,status) VALUES (?,?,?,?,?,?,?,?,?)', ('A-M1',21,31,1,'2026-08-22',100,150,50,'verified'))
    assert con.execute('SELECT COUNT(*) FROM meter_readings m JOIN pumps p ON p.id=m.pump_id JOIN pump_nozzles n ON n.id=m.nozzle_id WHERE m.station_id=1 AND p.station_id=1 AND n.pump_id=p.id AND m.closing_reading>=m.opening_reading AND m.sold_liters=(m.closing_reading-m.opening_reading)', ()).fetchone()[0] == 1
    # Quality is visible only through an in-scope refill.
    con.execute('INSERT INTO fuel_quality_tests(refill_id,test_date,density,result) VALUES (?,?,?,?)', (rid,'2026-08-22',0.83,'pass'))
    assert con.execute('SELECT COUNT(*) FROM fuel_quality_tests q JOIN tank_refills r ON r.id=q.refill_id WHERE r.station_id=1 AND r.is_deleted=0', ()).fetchone()[0] == 1
    # Calibration must bind the entity to the same station.
    con.execute('INSERT INTO calibration_records(calibration_code,entity_type,entity_id,station_id,calibration_date) VALUES (?,?,?,?,?)', ('A-C1','tank',11,1,'2026-08-22'))
    assert con.execute("SELECT COUNT(*) FROM calibration_records c JOIN tanks t ON lower(c.entity_type)='tank' AND c.entity_id=t.id WHERE c.station_id=1 AND t.station_id=1 AND t.is_deleted=0", ()).fetchone()[0] == 1
    assert con.execute("SELECT COUNT(*) FROM calibration_records c JOIN tanks t ON lower(c.entity_type)='tank' AND c.entity_id=t.id WHERE c.station_id=1 AND t.station_id=2", ()).fetchone()[0] == 0
    # COUNT + LIMIT/OFFSET + search/sort contract.
    total, rows = page(con, 'tanks', 1, limit=1, offset=0, sort='id', direction='DESC')
    assert total == 2 and len(rows) == 1 and rows[0][0] == 13
    total2, rows2 = page(con, 'tanks', 1, search='A-T1', limit=10, offset=0)
    assert total2 == 1 and len(rows2) == 1 and rows2[0][1] == 'A-T1'
    total3, rows3 = page(con, 'tank_refills', 1, limit=1, offset=1, sort='id', direction='ASC')
    assert total3 == 1 and rows3 == []
    print('MODULE-006 SQLite integration: PASS')


if __name__ == '__main__':
    main()
