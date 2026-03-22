-- Combined initial schema migration
-- Tables and schema setup (assumes database and user already exist)

-- Database and role must be provisioned outside Flyway (Flyway runs in a tx)

-- Ensure public schema exists and ownership is correct
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
    bpp_id TEXT,
    bpp_uri TEXT,
    network_id TEXT[],
    provider_id TEXT REFERENCES provider(id),
    catalog_id TEXT REFERENCES catalog(id),
    payload JSONB,
    schema_version VARCHAR(4) NOT NULL DEFAULT '2.0',
    created_by TEXT,
    updated_by TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);


-- Catalog indexes used by discovery-service JSONPath + metadata lookups
CREATE INDEX IF NOT EXISTS idx_catalog_context_url ON catalog(context_url);
CREATE INDEX IF NOT EXISTS idx_catalog_type ON catalog(type);
CREATE INDEX IF NOT EXISTS idx_catalog_payload_gin ON catalog USING GIN (payload jsonb_path_ops);

-- Provider indexes supporting joins from items -> providers -> catalogs
CREATE INDEX IF NOT EXISTS idx_provider_catalog_id ON provider(catalog_id);
CREATE INDEX IF NOT EXISTS idx_provider_context_url ON provider(context_url);
CREATE INDEX IF NOT EXISTS idx_provider_type ON provider(type);

-- Item indexes aligned with runtime filters
CREATE INDEX IF NOT EXISTS idx_item_catalog_id ON item(catalog_id);
CREATE INDEX IF NOT EXISTS idx_item_provider_id ON item(provider_id);
CREATE INDEX IF NOT EXISTS idx_item_type ON item(type);
CREATE INDEX IF NOT EXISTS idx_item_context_url ON item(context_url);
CREATE INDEX IF NOT EXISTS idx_item_payload_gin ON item USING GIN (payload jsonb_path_ops);

-- Pre-parsed geometry rows written by catalog-publish-job / GeometryExtractor.
-- path format: $.catalogs[*].beckn:items[*].beckn:availableAt[*].geo (absolute path from request targets).
CREATE TABLE IF NOT EXISTS item_location_collection (
    item_id TEXT NOT NULL REFERENCES item(id) ON DELETE CASCADE,
    path    TEXT NOT NULL,
    geom    GEOMETRY(Geometry, 4326) NOT NULL,
    PRIMARY KEY (item_id, path)
);
CREATE INDEX IF NOT EXISTS idx_ilc_geom_gist ON item_location_collection USING GIST (geom);
