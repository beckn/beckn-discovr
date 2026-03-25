ALTER TABLE item ADD COLUMN IF NOT EXISTS schema_version VARCHAR(4) NOT NULL DEFAULT '2.0';
CREATE INDEX IF NOT EXISTS idx_item_schema_version ON item (schema_version);
