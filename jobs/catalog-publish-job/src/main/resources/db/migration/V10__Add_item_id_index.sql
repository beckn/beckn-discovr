-- Standalone index on item.id for fast lookups by resource ID alone.
-- The composite PK (id, bpp_id) supports prefix lookups on id, but a dedicated
-- index guarantees optimal performance for cross-BPP offer resolution queries
-- (WHERE id IN (...)) at trillion-row scale.
--
-- Note: Using CREATE INDEX (not CONCURRENTLY) because Flyway wraps migrations
-- in a transaction and CONCURRENTLY cannot run inside a transaction.
-- For existing production deployments with large tables, run the CONCURRENTLY
-- variant manually outside of Flyway before deploying this migration.
CREATE INDEX IF NOT EXISTS idx_item_id ON item (id);
