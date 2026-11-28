-- GIN on item.payload for JSONPath filter queries (J / J+G).
-- Aligns production with discover IT schema (idx_item_payload_gin) and
-- architecture guidance for ≤150 ms engine p99 at 100M resources.
-- Uses jsonb_path_ops to match @@ / @? jsonpath predicates (not RFC 9535
-- typed subquery predicates from #489 — measure publish-side cost separately).
--
-- CONCURRENTLY avoids a long SHARE lock on item during the build (publish
-- startup must not block writers on large tables). Requires non-transactional
-- execution; plain DROP is used only for an INVALID leftover (IF NOT EXISTS
-- would otherwise skip rebuilding a failed concurrent index).
-- flyway:executeInTransaction=false

DO $fix$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM pg_class c
    JOIN pg_index i ON i.indexrelid = c.oid
    WHERE c.relname = 'idx_item_payload_gin'
      AND NOT i.indisvalid
  ) THEN
    EXECUTE 'DROP INDEX IF EXISTS idx_item_payload_gin';
  END IF;
END
$fix$;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_item_payload_gin
  ON item USING GIN (payload jsonb_path_ops);
