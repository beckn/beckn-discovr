-- Stores the registered network hierarchy used by the catalog router to resolve
-- parent-network topics. A row here means at least one subscriber exists for
-- that network (or its sub-trees), making it a valid routing target.
--
-- Hierarchy example:
--   bap.net/ev          (depth=0, root)
--   └─ bap.net/ev-charging (depth=1, parent=bap.net/ev)
CREATE TABLE IF NOT EXISTS networks (
    network_id        VARCHAR(100) PRIMARY KEY,
    parent_network_id VARCHAR(100) REFERENCES networks(network_id),
    display_name      VARCHAR(255),
    depth             INT NOT NULL DEFAULT 0,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Fast parent-lookup for hierarchy traversal.
CREATE INDEX IF NOT EXISTS idx_networks_parent ON networks(parent_network_id);

-- Self-referencing FK means children must be inserted after their parent.
-- A root network has parent_network_id IS NULL and depth = 0.
