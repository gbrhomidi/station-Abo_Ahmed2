-- Phase 2 rollback. Export/backup first.
-- SQLite cannot safely DROP an added column without table rebuild; this rollback
-- removes only newly-created tables/indexes. Added product/sale columns remain
-- nullable compatibility columns and must be retired in a reviewed rebuild.
DROP INDEX IF EXISTS idx_phase2_serials_warranty;
DROP INDEX IF EXISTS idx_phase2_serials_product_status;
DROP INDEX IF EXISTS idx_phase2_batches_fefo;
DROP TABLE IF EXISTS product_serials;
DROP TABLE IF EXISTS product_batches;
DROP TABLE IF EXISTS stock_locations;
