-- Push outcome rollup for the whole index pass, so the UI can see what happened WITHOUT
-- touching the proven-state invariant. Written on every pass; index_digest still advances
-- only on 'success' (see StateStore.upsertIndexState vs recordIndexOutcome).
--
-- sync_status ∈ { success, partial, failed }:
--   success  — every pushed part ACKed (200); index_digest advanced.
--   partial  — some parts ACKed, at least one failed; index_digest NOT advanced (failed parts retry).
--   failed   — no part ACKed; index_digest NOT advanced.
-- error_detail — JSON array of the failed parts, each { catalogId, partUrl, httpStatus, detail },
--                so a failure is attributable to index_url (row PK) → catalog → part. NULL on success.
ALTER TABLE index_crawl_state ADD COLUMN IF NOT EXISTS sync_status  TEXT;
ALTER TABLE index_crawl_state ADD COLUMN IF NOT EXISTS error_detail TEXT;
