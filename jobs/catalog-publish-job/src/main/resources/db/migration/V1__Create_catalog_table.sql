-- Combined initial schema (catalog, provider, item) — catalog/provider tables kept for compatibility; job does not write to them.
DO $$
BEGIN
  IF current_database() <> 'catalog_db' THEN
    RAISE EXCEPTION 'Flyway migrations must run against catalog_db (current: %)', current_database();
  END IF;
END$$;

CREATE SCHEMA IF NOT EXISTS public;
ALTER SCHEMA public OWNER TO catalog_user;
ALTER DATABASE catalog_db OWNER TO catalog_user;

CREATE TABLE IF NOT EXISTS catalog (
    id TEXT PRIMARY KEY,
    name TEXT,
    context_url TEXT,
    type TEXT,
    bpp_id TEXT,
    bpp_uri TEXT,
    network_id TEXT,
    payload JSONB,
    created_by TEXT,
    updated_by TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS provider (
    id TEXT PRIMARY KEY,
    name TEXT,
    context_url TEXT,
    type TEXT,
    bpp_id TEXT,
    bpp_uri TEXT,
    network_id TEXT,
    catalog_id TEXT REFERENCES catalog(id),
    payload JSONB,
    created_by TEXT,
    updated_by TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS item (
    id TEXT PRIMARY KEY,
    name TEXT,
    context_url TEXT,
    type TEXT,
    bpp_id TEXT NOT NULL,
    bpp_uri TEXT,
    network_id TEXT[],
    provider_id TEXT REFERENCES provider(id),
    catalog_id TEXT REFERENCES catalog(id),
    payload JSONB,
    created_by TEXT,
    updated_by TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
