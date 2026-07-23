-- Link crawl state to the provider it came from, using the provider's DeDi identity
-- (the manifest "domain" = bppId). This replaces the fragile URL/host matching: the
-- crawler stamps provider_domain on every crawled row, and records the resolved
-- domain + name back onto the source so the UI can join provider → index → catalogs
-- exactly and show the real provider name.

ALTER TABLE index_crawl_state  ADD COLUMN IF NOT EXISTS provider_domain TEXT;
ALTER TABLE catalog_part_state ADD COLUMN IF NOT EXISTS provider_domain TEXT;

-- Resolved from the manifest after the first successful crawl (null until then).
ALTER TABLE crawler_source ADD COLUMN IF NOT EXISTS provider_domain TEXT;
ALTER TABLE crawler_source ADD COLUMN IF NOT EXISTS provider_name   TEXT;

CREATE INDEX IF NOT EXISTS ix_index_crawl_state_provider  ON index_crawl_state (provider_domain);
CREATE INDEX IF NOT EXISTS ix_catalog_part_state_provider ON catalog_part_state (provider_domain);
