-- Squashed migration (V1–V4). Requires a clean schema — do not apply to a DB
-- that already has any of these tables from a prior partial migration.
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE item (
    id                  TEXT          NOT NULL,
    catalog_id          TEXT          NOT NULL,
    context_url         TEXT,
    type                TEXT,
    network_id          TEXT[],
    offer_ids           TEXT[]        NOT NULL DEFAULT '{}',
    payload             JSONB,
    created_at          TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, catalog_id)
);
CREATE INDEX idx_item_context_url ON item(context_url);
CREATE INDEX idx_item_type ON item(type);
CREATE INDEX idx_item_updated_at ON item(updated_at DESC);
CREATE INDEX idx_item_offer_ids ON item USING GIN(offer_ids);
CREATE INDEX idx_item_catalog ON item(catalog_id);

CREATE TABLE item_location_collection (
    item_id             TEXT          NOT NULL,
    catalog_id          TEXT          NOT NULL,
    path                TEXT          NOT NULL,
    geom                GEOMETRY(Geometry, 4326) NOT NULL,
    PRIMARY KEY (item_id, catalog_id, path)
);
CREATE INDEX idx_ilc_geom_gist ON item_location_collection USING GIST(geom);
CREATE INDEX idx_ilc_geog_gist ON item_location_collection USING GIST((geom::geography));
CREATE INDEX idx_ilc_catalog_id ON item_location_collection(catalog_id);

-- Provider-level offers: offers published without resourceIds.
-- Stored once per (offer_id, catalog_id) — no write amplification into item payloads.
-- Resolved at search time by ProviderOfferEnricher using provider_id + catalog_id lookup.
CREATE TABLE provider_offer (
    offer_id        TEXT          NOT NULL,
    catalog_id      TEXT          NOT NULL,
    provider_id     TEXT          NOT NULL,
    payload         JSONB         NOT NULL,
    created_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (offer_id, catalog_id)
);
CREATE INDEX idx_provider_offer_provider ON provider_offer(provider_id);
CREATE INDEX idx_provider_offer_catalog ON provider_offer(catalog_id);
CREATE INDEX idx_provider_offer_provider_catalog ON provider_offer(provider_id, catalog_id);
