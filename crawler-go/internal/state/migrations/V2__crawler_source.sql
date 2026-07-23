-- Manifest sources the crawler should crawl, when crawler.source=db.
-- Populated by the UI (each row = one full DeDi manifest URL). The crawler reads the
-- active rows (status = true) on every index poll, so UI changes take effect within a poll.
CREATE TABLE IF NOT EXISTS crawler_source (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  dedi_url     TEXT NOT NULL UNIQUE,   -- full manifest URL
  display_name TEXT,                   -- human label for logs / UI
  status       BOOLEAN NOT NULL DEFAULT true,  -- only true rows are crawled
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
