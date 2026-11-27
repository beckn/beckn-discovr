-- GIN on item.payload for JSONPath filter queries (J / J+G).
-- Aligns production with discover IT schema (idx_item_payload_gin) and
-- architecture guidance for ≤150 ms engine p99 at 100M resources.
-- Uses jsonb_path_ops to match @@ jsonpath predicates.
CREATE INDEX IF NOT EXISTS idx_item_payload_gin ON item USING GIN (payload jsonb_path_ops);
