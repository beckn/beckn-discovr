-- Composite index for FULL replace delete queries.
-- Covers: DELETE FROM item WHERE catalog_id = ? AND bpp_id = ?
-- Without this index, FULL replace does a full table scan at scale.
CREATE INDEX IF NOT EXISTS idx_item_catalog_bpp ON item (catalog_id, bpp_id);
