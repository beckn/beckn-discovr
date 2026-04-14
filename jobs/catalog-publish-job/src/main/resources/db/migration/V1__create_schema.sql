CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS item (
    id                  TEXT          NOT NULL,
    catalog_id          TEXT          NOT NULL,
    context_url         TEXT,
    type                TEXT,
    network_id          TEXT[],
    offer_ids           TEXT[]        NOT NULL DEFAULT '{}',
    payload             JSONB,
    created_by          VARCHAR(255),
    updated_by          VARCHAR(255),
    created_at          TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, catalog_id)
);
CREATE INDEX idx_item_context_url ON item(context_url);
CREATE INDEX idx_item_type ON item(type);
CREATE INDEX idx_item_updated_at ON item(updated_at DESC);
CREATE INDEX idx_item_offer_ids ON item USING GIN(offer_ids);
CREATE INDEX idx_item_catalog ON item(catalog_id);
CREATE INDEX idx_item_id ON item(id);

CREATE TABLE IF NOT EXISTS item_location_collection (
    item_id             VARCHAR(255)  NOT NULL,
    path                TEXT          NOT NULL,
    geom                GEOMETRY(Geometry, 4326) NOT NULL,
    PRIMARY KEY (item_id, path)
);
CREATE INDEX idx_item_location_geom ON item_location_collection USING GIST(geom);
CREATE INDEX idx_item_location_geog ON item_location_collection USING GIST((geom::geography));
