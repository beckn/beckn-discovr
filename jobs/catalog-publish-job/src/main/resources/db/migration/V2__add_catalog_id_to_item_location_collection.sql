-- Add catalog_id to item_location_collection so location rows are scoped per catalog.
-- Previously the PK was (item_id, path) — ambiguous when the same item_id appears in
-- multiple catalogs. The new PK (item_id, catalog_id, path) makes the scope unambiguous
-- and allows FULL-replace DELETE to use a simple WHERE without a JOIN to the item table.

ALTER TABLE item_location_collection ADD COLUMN IF NOT EXISTS catalog_id TEXT NOT NULL DEFAULT '';

-- Drop the old PK and recreate with catalog_id included.
ALTER TABLE item_location_collection DROP CONSTRAINT IF EXISTS item_location_collection_pkey;
ALTER TABLE item_location_collection ADD PRIMARY KEY (item_id, catalog_id, path);

-- Recreate spatial indexes (names change to avoid conflicts).
DROP INDEX IF EXISTS idx_item_location_geom;
DROP INDEX IF EXISTS idx_item_location_geog;
CREATE INDEX IF NOT EXISTS idx_ilc_geom_gist ON item_location_collection USING GIST (geom);
CREATE INDEX IF NOT EXISTS idx_ilc_geog_gist ON item_location_collection USING GIST ((geom::geography));
CREATE INDEX IF NOT EXISTS idx_ilc_catalog_id ON item_location_collection (catalog_id);
