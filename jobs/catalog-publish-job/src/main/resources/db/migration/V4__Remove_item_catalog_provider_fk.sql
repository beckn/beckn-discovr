-- Drop FK constraints — item no longer links to catalog or provider tables for catalog-publish-job writes.
ALTER TABLE item DROP CONSTRAINT IF EXISTS fk_item_catalog;
ALTER TABLE item DROP CONSTRAINT IF EXISTS fk_item_provider;
ALTER TABLE item DROP CONSTRAINT IF EXISTS item_catalog_id_fkey;
ALTER TABLE item DROP CONSTRAINT IF EXISTS item_provider_id_fkey;

ALTER TABLE item ALTER COLUMN catalog_id DROP NOT NULL;
ALTER TABLE item ALTER COLUMN provider_id DROP NOT NULL;
