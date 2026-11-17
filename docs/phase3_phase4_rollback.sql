-- Phase 3/4 rollback. Backup first. Drops only additive phase tables.
DROP INDEX IF EXISTS idx_phase3_events_pending;
DROP INDEX IF EXISTS idx_phase3_po_station_status;
DROP TABLE IF EXISTS role_scopes;
DROP TABLE IF EXISTS fuel_temperature_coefficients;
DROP TABLE IF EXISTS inventory_health_snapshots;
DROP TABLE IF EXISTS inventory_anomalies;
DROP TABLE IF EXISTS inventory_forecasts;
DROP TABLE IF EXISTS events_outbox;
DROP TABLE IF EXISTS purchase_order_lines;
DROP TABLE IF EXISTS purchase_orders;
