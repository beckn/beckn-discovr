-- GIN on item.payload for JSONPath filter queries (J / J+G).
-- Aligns production with discover IT schema (idx_item_payload_gin) and
-- architecture guidance for ≤150 ms engine p99 at 100M resources.
-- Uses jsonb_path_ops to match @@ / @? jsonpath predicates (not RFC 9535
-- typed subquery predicates from #489 — measure publish-side cost separately).
--
-- Transactional CREATE INDEX (Flyway-safe). A prior attempt used
-- CREATE INDEX CONCURRENTLY + a DO cleanup block, but Flyway rejects mixing
-- transactional and non-transactional statements (mixed=false), and a long
-- concurrent build at publish-job startup can outlast Helm probes → INVALID
-- index + crash-loop.
--
-- Large live tables: pre-create during a maintenance window before rolling
-- this migration so Flyway's IF NOT EXISTS no-ops:
--   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_item_payload_gin
--     ON item USING GIN (payload jsonb_path_ops);
CREATE INDEX IF NOT EXISTS idx_item_payload_gin
  ON item USING GIN (payload jsonb_path_ops);
