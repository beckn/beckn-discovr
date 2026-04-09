-- Drop the composite PK (id, bpp_id) introduced in V7 and make id the sole primary key.
-- Rationale: bpp_id / bpp_uri are now sourced from the catalog object itself during
-- persistence (not the Beckn context). They are optional per the publish contract —
-- when the catalog does not carry them, the row is inserted with null bpp_id / bpp_uri.
--
-- Data impact: zero data loss. DROP CONSTRAINT and ADD PRIMARY KEY (id) preserve all rows.
-- ADD PRIMARY KEY (id) will fail if duplicate ids exist across different bpp_ids.
-- Run this duplicate check first on any environment that has historical data:
--   SELECT id, COUNT(*) FROM item GROUP BY id HAVING COUNT(*) > 1;
-- If the query returns rows, resolve the duplicates manually before running this migration.
BEGIN;

ALTER TABLE item DROP CONSTRAINT item_pkey;
ALTER TABLE item ADD PRIMARY KEY (id);

-- Relax NOT NULL constraints on BPP columns so persistence can tolerate absent values.
-- bpp_uri was already nullable in V1; the statement is idempotent and kept for clarity.
ALTER TABLE item ALTER COLUMN bpp_id DROP NOT NULL;
ALTER TABLE item ALTER COLUMN bpp_uri DROP NOT NULL;

COMMIT;
