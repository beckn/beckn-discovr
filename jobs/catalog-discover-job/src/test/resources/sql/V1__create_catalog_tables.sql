-- Combined initial schema migration
-- Tables and schema setup (assumes database and user already exist)

-- Database and role must be provisioned outside Flyway (Flyway runs in a tx)

-- Ensure public schema exists and ownership is correct
CREATE SCHEMA IF NOT EXISTS public;
ALTER SCHEMA public OWNER TO catalog_user;
ALTER DATABASE catalog_db OWNER TO catalog_user;

CREATE TABLE IF NOT EXISTS item (
    id          TEXT          NOT NULL,
    catalog_id  TEXT          NOT NULL,
    context_url TEXT,
    type        TEXT,
    network_id  TEXT[],
    offer_ids   TEXT[]        NOT NULL DEFAULT '{}',
    payload     JSONB,
    created_by  TEXT,
    updated_by  TEXT,
    created_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, catalog_id)
);

-- Item indexes aligned with runtime filters
CREATE INDEX IF NOT EXISTS idx_item_catalog_id  ON item(catalog_id);
CREATE INDEX IF NOT EXISTS idx_item_type        ON item(type);
CREATE INDEX IF NOT EXISTS idx_item_context_url ON item(context_url);
CREATE INDEX IF NOT EXISTS idx_item_updated_at  ON item(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_item_payload_gin ON item USING GIN (payload jsonb_path_ops);

-- Pre-parsed geometry rows written by catalog-publish-job / GeometryExtractor.
-- path format: $.catalogs[*].resources[*].availableAt[*].geo (absolute path from request targets).
-- catalog_id scopes each row to the owning catalog, preventing cross-catalog contamination
-- when the same item_id exists in multiple catalogs (item PK is (id, catalog_id)).
CREATE TABLE IF NOT EXISTS item_location_collection (
    item_id    TEXT NOT NULL,
    catalog_id TEXT NOT NULL DEFAULT '',
    path       TEXT NOT NULL,
    geom       GEOMETRY(Geometry, 4326) NOT NULL,
    PRIMARY KEY (item_id, catalog_id, path)
);
CREATE INDEX IF NOT EXISTS idx_ilc_geom_gist ON item_location_collection USING GIST (geom);
CREATE INDEX IF NOT EXISTS idx_ilc_geog_gist ON item_location_collection USING GIST ((geom::geography));
CREATE INDEX IF NOT EXISTS idx_ilc_catalog_id ON item_location_collection (catalog_id);
