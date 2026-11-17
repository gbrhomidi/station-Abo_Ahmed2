-- Phase 1 Wet Stock additive migration
-- Run only after a complete SQLite backup and integrity_check.
-- Existing tanks/shifts tables and IDs are intentionally preserved.

CREATE TABLE IF NOT EXISTS tank_readings (
  reading_id INTEGER PRIMARY KEY AUTOINCREMENT,
  tank_id INTEGER NOT NULL REFERENCES tanks(id),
  reading_at TEXT NOT NULL,
  current_liters REAL NOT NULL CHECK(current_liters >= 0),
  temperature_c REAL,
  water_level_mm REAL,
  source TEXT NOT NULL DEFAULT 'manual' CHECK(source IN ('ATG','dip','manual')),
  recorded_by INTEGER REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_phase1_tank_readings_tank_at
  ON tank_readings(tank_id, reading_at DESC);

CREATE TABLE IF NOT EXISTS inventory_reason_codes (
  code TEXT PRIMARY KEY,
  label_ar TEXT NOT NULL,
  label_en TEXT,
  applies_to TEXT DEFAULT 'BOTH' CHECK(applies_to IN ('PRODUCT','FUEL','BOTH')),
  requires_note INTEGER NOT NULL DEFAULT 0
);
INSERT OR IGNORE INTO inventory_reason_codes(code,label_ar,label_en,applies_to,requires_note) VALUES
 ('LEAKAGE','تسرب','Leakage','FUEL',1),
 ('EVAPORATION','تبخر','Evaporation','FUEL',1),
 ('METER_ERROR','خطأ قراءة','Meter error','FUEL',1),
 ('THEFT','سرقة','Theft','FUEL',1),
 ('PUMP_DIFF','فرق مضخة','Pump difference','FUEL',1),
 ('DIP_VARIANCE','فرق غمس','Dip variance','FUEL',1),
 ('MANUAL_ADJUSTMENT','تسوية يدوية','Manual adjustment','BOTH',1);

CREATE TABLE IF NOT EXISTS tank_dip_readings (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tank_id INTEGER NOT NULL REFERENCES tanks(id),
  dip_cm REAL NOT NULL CHECK(dip_cm >= 0),
  dip_liters REAL NOT NULL CHECK(dip_liters >= 0),
  book_liters REAL NOT NULL,
  variance_liters REAL NOT NULL,
  variance_percent REAL NOT NULL,
  tolerance_exceeded INTEGER NOT NULL DEFAULT 0,
  reason_code TEXT REFERENCES inventory_reason_codes(code),
  shift_id INTEGER REFERENCES shifts(id),
  notes TEXT,
  created_by INTEGER REFERENCES users(id),
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_phase1_dip_tank_at
  ON tank_dip_readings(tank_id, created_at DESC);

CREATE TABLE IF NOT EXISTS shift_reconciliations (
  recon_id INTEGER PRIMARY KEY AUTOINCREMENT,
  shift_id INTEGER NOT NULL REFERENCES shifts(id),
  tank_id INTEGER NOT NULL REFERENCES tanks(id),
  opening_liters REAL NOT NULL,
  deliveries_liters REAL NOT NULL DEFAULT 0,
  sales_liters REAL NOT NULL DEFAULT 0,
  adjustments_liters REAL NOT NULL DEFAULT 0,
  expected_liters REAL NOT NULL,
  actual_liters REAL NOT NULL,
  variance_liters REAL NOT NULL,
  variance_pct REAL NOT NULL,
  cause TEXT REFERENCES inventory_reason_codes(code),
  notes TEXT,
  approved_by INTEGER REFERENCES users(id),
  approved_at TEXT,
  UNIQUE(shift_id, tank_id)
);
CREATE INDEX IF NOT EXISTS idx_phase1_recon_shift_tank
  ON shift_reconciliations(shift_id, tank_id);
