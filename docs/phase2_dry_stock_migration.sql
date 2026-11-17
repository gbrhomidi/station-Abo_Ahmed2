-- Phase 2 additive dry-stock migration. Backup and integrity_check first.
ALTER TABLE products ADD COLUMN xyz_class TEXT;
ALTER TABLE products ADD COLUMN reorder_point REAL DEFAULT 0;
ALTER TABLE products ADD COLUMN safety_stock REAL DEFAULT 0;
ALTER TABLE products ADD COLUMN lead_time_days INTEGER DEFAULT 0;
ALTER TABLE products ADD COLUMN track_serial INTEGER DEFAULT 0;
ALTER TABLE products ADD COLUMN track_batch INTEGER DEFAULT 0;
ALTER TABLE products ADD COLUMN track_expiry INTEGER DEFAULT 0;
ALTER TABLE products ADD COLUMN track_warranty INTEGER DEFAULT 0;
ALTER TABLE products ADD COLUMN unit_of_measure TEXT;
CREATE TABLE IF NOT EXISTS stock_locations(location_id INTEGER PRIMARY KEY AUTOINCREMENT,warehouse_id INTEGER NOT NULL REFERENCES warehouses(id),name TEXT NOT NULL,type TEXT NOT NULL DEFAULT 'warehouse',is_active INTEGER NOT NULL DEFAULT 1);
CREATE TABLE IF NOT EXISTS product_batches(batch_id INTEGER PRIMARY KEY AUTOINCREMENT,product_id INTEGER NOT NULL REFERENCES products(id),lot_number TEXT,expiry_date TEXT,quantity REAL NOT NULL DEFAULT 0,location_id INTEGER REFERENCES stock_locations(location_id),received_at TEXT NOT NULL,received_by INTEGER REFERENCES users(id));
CREATE INDEX IF NOT EXISTS idx_phase2_batches_fefo ON product_batches(product_id,expiry_date,received_at,batch_id);
CREATE TABLE IF NOT EXISTS product_serials(serial_id INTEGER PRIMARY KEY AUTOINCREMENT,product_id INTEGER NOT NULL REFERENCES products(id),serial_no TEXT UNIQUE NOT NULL,batch_no TEXT,supplier_id INTEGER REFERENCES parties(id),received_at TEXT,warranty_start_at TEXT,warranty_end_at TEXT,expiry_at TEXT,status TEXT NOT NULL DEFAULT 'AVAILABLE',sale_id INTEGER REFERENCES sales_transactions(id),location_id INTEGER REFERENCES stock_locations(location_id),return_reason TEXT,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE INDEX IF NOT EXISTS idx_phase2_serials_product_status ON product_serials(product_id,status);
CREATE INDEX IF NOT EXISTS idx_phase2_serials_warranty ON product_serials(warranty_end_at);
ALTER TABLE sale_items ADD COLUMN batch_id INTEGER REFERENCES product_batches(batch_id);
ALTER TABLE sale_items ADD COLUMN serial_id INTEGER REFERENCES product_serials(serial_id);
