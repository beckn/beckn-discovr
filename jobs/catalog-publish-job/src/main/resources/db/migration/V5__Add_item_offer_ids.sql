-- offer_ids: array of offer IDs; GIN index for fast array-containment query.
ALTER TABLE item ADD COLUMN IF NOT EXISTS offer_ids TEXT[] NOT NULL DEFAULT '{}';
CREATE INDEX IF NOT EXISTS idx_item_offer_ids ON item USING GIN (offer_ids);
