-- Drop unused catalog and provider tables.
-- Discovr stores all data denormalized in the item table + Elasticsearch.
-- These tables were created in V1 but never written to by the publish pipeline.
-- FK constraints were already removed in V4.
DROP TABLE IF EXISTS provider CASCADE;
DROP TABLE IF EXISTS catalog CASCADE;
