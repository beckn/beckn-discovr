-- #306: a provider can publish multiple geometries under a single wildcard path,
-- e.g. $.catalogs[*].provider.availableAt[*].geo. The previous primary key
-- (item_id, catalog_id, path) made those collide, so only the last-written geometry
-- survived — combined / PostGIS spatial queries (J+G, Offer+G, the J+G+T chain step)
-- then matched only one of the provider's locations.
--
-- Add a per-path ordinal `seq` and include it in the primary key so every location
-- becomes its own row. The stored `path` stays a wildcard, so the discovery side's
-- `ilc.path = ?` matching and its EXISTS spatial subquery are unchanged (EXISTS already
-- returns true if ANY row matches → "any location" semantics preserved for all operators).
--
-- Existing rows: there is currently exactly one row per (item_id, catalog_id, path), so
-- ADD COLUMN ... DEFAULT 0 makes every existing row seq=0 and they stay unique → the new
-- PK holds with no violation. On PG11+ this is a metadata-only column add (no table rewrite).
--
-- The DEFAULT 0 is intentionally RETAINED (not dropped) for rolling-deploy safety: an old
-- publish-job instance whose entity has no `seq` field still INSERTs successfully (seq=0,
-- which merge-collapses to a single row = the pre-fix behaviour) instead of hitting a
-- NOT NULL violation. New instances set seq explicitly (0,1,2…) to store every location.
ALTER TABLE item_location_collection ADD COLUMN seq SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE item_location_collection DROP CONSTRAINT item_location_collection_pkey;
ALTER TABLE item_location_collection ADD CONSTRAINT item_location_collection_pkey
    PRIMARY KEY (item_id, catalog_id, path, seq);
