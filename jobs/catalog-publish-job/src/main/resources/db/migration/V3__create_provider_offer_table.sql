-- Provider-level offers: offers published without resourceIds.
-- Stored once per (offer_id, catalog_id) — no write amplification into item payloads.
-- Resolved at search time by ProviderOfferEnricher using provider_id lookup.

CREATE TABLE IF NOT EXISTS provider_offer (
    offer_id        TEXT          NOT NULL,
    catalog_id      TEXT          NOT NULL,
    provider_id     TEXT          NOT NULL,
    payload         JSONB         NOT NULL,
    created_by      VARCHAR(255),
    updated_by      VARCHAR(255),
    subscriber_id   VARCHAR(255),
    created_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (offer_id, catalog_id)
);
CREATE INDEX idx_provider_offer_provider ON provider_offer(provider_id);
CREATE INDEX idx_provider_offer_catalog ON provider_offer(catalog_id);
