-- PostGIS geometry table for item locations (GIST indexes for spatial queries)
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS item_location_collection (
    item_id VARCHAR(255) NOT NULL,
    path    TEXT         NOT NULL,
    geom    GEOMETRY(Geometry, 4326) NOT NULL,
    PRIMARY KEY (item_id, path)
);

CREATE INDEX IF NOT EXISTS idx_item_location_geom
    ON item_location_collection USING GIST (geom);

CREATE INDEX IF NOT EXISTS idx_item_location_geog
    ON item_location_collection USING GIST ((geom::geography));
