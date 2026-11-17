-- Phase 1 Wet Stock rollback
-- Confirm a verified backup exists before running.
-- This removes only objects introduced by phase1_wet_stock_migration.sql.

DROP INDEX IF EXISTS idx_phase1_recon_shift_tank;
DROP INDEX IF EXISTS idx_phase1_dip_tank_at;
DROP INDEX IF EXISTS idx_phase1_tank_readings_tank_at;
DROP TABLE IF EXISTS shift_reconciliations;
DROP TABLE IF EXISTS tank_dip_readings;
DROP TABLE IF EXISTS inventory_reason_codes;
DROP TABLE IF EXISTS tank_readings;
