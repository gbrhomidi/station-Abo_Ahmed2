-- V72 rollback plan. Run only after a full SQLite backup and with the app stopped.
-- This removes only V72-created rows/tables; legacy inventory and purchase-order rows remain.
DROP INDEX IF EXISTS uq_tank_refills_station_receipt_key;
DROP INDEX IF EXISTS idx_fuel_stock_movements_station_tank;
DROP INDEX IF EXISTS idx_supplier_invoices_station_po;
DROP TABLE IF EXISTS fuel_stock_movements;
DROP TABLE IF EXISTS supplier_invoice_lines;
DROP TABLE IF EXISTS supplier_invoices;
-- SQLite cannot safely drop columns on all supported versions. Leave the additive
-- tank_refills columns (po_id, po_line_id, receipt_id, receipt_key) in place; they
-- are nullable and harmless to V71 readers. Restore PRAGMA user_version to 71 only
-- after validating the schema and reinstalling the V71 binary.
PRAGMA user_version = 71;
