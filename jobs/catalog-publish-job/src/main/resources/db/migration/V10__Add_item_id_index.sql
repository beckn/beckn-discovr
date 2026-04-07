-- Standalone index on item.id for fast lookups by resource ID alone.
-- The composite PK (id, bpp_id) supports prefix lookups on id, but a dedicated
-- index guarantees optimal performance for cross-BPP offer resolution queries
-- (WHERE id IN (...)) at trillion-row scale.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_item_id ON item (id);
