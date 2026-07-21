-- Crawler state (design doc §5.3). Two URL-keyed tables holding PROVEN digests only
-- (written after the bytes verified + the push returned 200), so a crash re-does, never skips.

-- "Did anything change for this provider?" — one row per index (= per provider for the POC).
CREATE TABLE IF NOT EXISTS index_crawl_state (
  index_url     TEXT PRIMARY KEY,   -- manifest files[].url
  manifest_etag TEXT,               -- optional ETag on /.well-known/dedi.json (deferred use)
  index_etag    TEXT,               -- optional ETag on the index (deferred use)
  index_digest  TEXT,               -- last accepted index digest (vs manifest files[].digest)
  next_update   TIMESTAMPTZ,        -- provider re-crawl hint
  last_seen_at  TIMESTAMPTZ
);

-- "Did this catalog change, and is it a rollback?" — one row per catalog part.
CREATE TABLE IF NOT EXISTS catalog_part_state (
  part_url          TEXT PRIMARY KEY,   -- records[].details.parts[].url
  catalog_id        TEXT NOT NULL,      -- records[].details.catalogId
  version           BIGINT,             -- records[].details.version (rollback guard)
  digest            TEXT,               -- last verified part digest
  source_updated_at TIMESTAMPTZ,        -- parts[].lastModified
  last_seen_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_catalog_part_state_catalog_id ON catalog_part_state (catalog_id);
