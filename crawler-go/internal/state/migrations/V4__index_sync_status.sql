-- Push outcome rollup for the whole index pass, so the UI can see what happened. index_digest is
-- advanced to the current index digest on EVERY pass (success or not); retry is gated on sync_status,
-- not on a stale digest: the poll skips an index only when the digest is unchanged AND
-- sync_status='success' (see Crawler.pollIndex). A 'partial'/'failed' index is re-diffed even with an
-- unchanged digest, so Differ re-pushes only the still-failed parts (ACKed parts are SKIP_UNCHANGED).
--
-- sync_status ∈ { success, partial, failed }:
--   success  — every pushed part ACKed (200).
--   partial  — some parts ACKed, at least one failed; re-diffed next poll until it reaches success.
--   failed   — no part ACKed; re-diffed next poll until it reaches success.
-- error_detail — JSON array of the failed parts, each { catalogId, partUrl, httpStatus, detail },
--                so a failure is attributable to index_url (row PK) → catalog → part. NULL on success.
ALTER TABLE index_crawl_state ADD COLUMN IF NOT EXISTS sync_status  TEXT;
ALTER TABLE index_crawl_state ADD COLUMN IF NOT EXISTS error_detail TEXT;
