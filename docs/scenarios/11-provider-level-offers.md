# 11 — Provider-Level Offers (No `resourceIds`)

## Overview

Provider-level offers are offers published **without `resourceIds`** (or with an empty `resourceIds: []`). They apply to ALL resources from that provider. Unlike resource-level offers (which are stamped on specific items), provider-level offers are stored in the `provider_offer` table and resolved at discovery-time by `ProviderOfferEnricher`.

## Key design rules

- Offer without `resourceIds` → classified as provider-level (stored in `provider_offer` table)
- Offer with `resourceIds: []` (empty array) → also classified as provider-level
- Provider-level offers are **NOT** stamped on individual item payloads
- Provider-level offers are resolved at **search time** by `ProviderOfferEnricher` — appended to every catalog from that provider
- `ProviderOfferEnricher` runs **AFTER** `CatalogPipeline` so `filterOffersByResourceIds` never sees them (they have no resourceIds and would be incorrectly filtered out)
- `provider_offer` PK is `(offer_id, catalog_id)` — scoped per catalog
- `provider_id` column links the offer to a provider
- `payload` column stores the full offer JSON
- MERGE mode: upserts new provider offers, preserves existing ones
- FULL replace: deletes ALL provider offers for that catalog, then inserts new ones
- Missing `provider.id` on catalog → provider offers are **skipped** (not persisted)

## Prerequisites

- Discovr stack running (catalog-publish + catalog-discover + elasticsearch + postgres)
- Resources from 01-catalog-ingestion SC-01 exist in DB (at least one catalog with provider `prov-abc` or similar)

---

## Test data setup

```bash
TS=$(date +%s)
PROV_ID="prov-plvl-${TS}"
CAT_ID="CAT-PLVL-${TS}"
BPP_ID="bpp-plvl-${TS}.in"
```

---

## Scenario Group 1: Publish with Provider-Level Offer (Happy Path)

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-PO-01 | Publish catalog with resources + 1 resource-level offer + 1 provider-level offer (no resourceIds) | POST `/catalog/push` | HTTP 202 ACK |
| SC-PO-02 | Resource-level offer stamped on item payload | `SELECT payload FROM item WHERE id = 'R1-${TS}'` | Payload contains `offer-item-${TS}` |
| SC-PO-03 | Provider-level offer NOT stamped on item payload | `SELECT payload FROM item WHERE id = 'R1-${TS}'` | Payload does NOT contain `offer-prov-${TS}` |
| SC-PO-04 | Provider-level offer stored in `provider_offer` table | `SELECT * FROM provider_offer WHERE offer_id = 'offer-prov-${TS}'` | Row exists with `provider_id = '${PROV_ID}'`, `catalog_id = '${CAT_ID}'`, payload contains offer JSON |
| SC-PO-05 | `provider_offer.created_at` and `updated_at` populated | Same row from SC-PO-04 | Both timestamps non-null |

### SC-PO-01 payload

```json
{
  "context": {
    "version": "2.0.0",
    "action": "catalog/push",
    "networkId": "verify-net",
    "bppId": "${BPP_ID}",
    "bppUri": "http://${BPP_ID}",
    "messageId": "<uuid>",
    "transactionId": "<uuid>",
    "timestamp": "<ISO-8601>"
  },
  "message": {
    "catalogs": [{
      "id": "${CAT_ID}",
      "provider": { "id": "${PROV_ID}" },
      "descriptor": { "name": "Provider Offer Test Catalog" },
      "resources": [{
        "id": "R1-${TS}",
        "descriptor": { "name": "Widget Alpha" },
        "resourceAttributes": {
          "@context": "https://schema.org/",
          "@type": "Product",
          "price": "99.99"
        }
      }, {
        "id": "R2-${TS}",
        "descriptor": { "name": "Widget Beta" },
        "resourceAttributes": {
          "@context": "https://schema.org/",
          "@type": "Product",
          "price": "149.99"
        }
      }],
      "offers": [
        {
          "id": "offer-item-${TS}",
          "descriptor": { "name": "Item-Level 10% Off" },
          "resourceIds": ["R1-${TS}"],
          "offerAttributes": {
            "@context": "https://schema.org",
            "@type": "Offer",
            "discount": "10%"
          }
        },
        {
          "id": "offer-prov-${TS}",
          "descriptor": { "name": "Provider-Wide 20% Off" },
          "offerAttributes": {
            "@context": "https://schema.org",
            "@type": "Offer",
            "discount": "20%"
          }
        }
      ]
    }]
  }
}
```

### Verification queries

```bash
# Check item payload does NOT contain provider offer
docker exec discovery-service-postgres psql -U catalog_user -d catalog_db -t -c \
  "SELECT payload FROM item WHERE id = 'R1-${TS}' AND catalog_id = '${CAT_ID}';"

# Check provider_offer table
docker exec discovery-service-postgres psql -U catalog_user -d catalog_db -t -c \
  "SELECT offer_id, provider_id, catalog_id, payload, created_at, updated_at FROM provider_offer WHERE offer_id = 'offer-prov-${TS}';"
```

---

## Scenario Group 2: Empty `resourceIds` Array

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-PO-06 | Offer with `resourceIds: []` (empty array) classified as provider-level | POST `/catalog/push` with offer having `"resourceIds": []` | HTTP 202 ACK |
| SC-PO-07 | Empty-resourceIds offer stored in `provider_offer` | `SELECT * FROM provider_offer WHERE offer_id = 'offer-empty-${TS}'` | Row exists with provider_id and payload |
| SC-PO-08 | Empty-resourceIds offer NOT stamped on item | `SELECT payload FROM item WHERE id = 'R1-${TS}'` | Payload does NOT contain `offer-empty-${TS}` |

### SC-PO-06 payload (offer section only)

```json
{
  "id": "offer-empty-${TS}",
  "descriptor": { "name": "Empty ResourceIds Offer" },
  "resourceIds": [],
  "offerAttributes": {
    "@context": "https://schema.org",
    "@type": "Offer",
    "discount": "15%"
  }
}
```

---

## Scenario Group 3: Discovery-Time Enrichment

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-PO-09 | Discover API returns provider-level offers alongside resource-level offers | POST `/beckn/discover` with query matching R1/R2 | Response catalog's `offers` array includes BOTH `offer-item-${TS}` (resource-level) AND `offer-prov-${TS}` (provider-level) |
| SC-PO-10 | Provider-level offer attached to ALL catalogs from that provider | POST `/beckn/discover` | Every catalog with `provider.id = ${PROV_ID}` has `offer-prov-${TS}` in its `offers` array |
| SC-PO-11 | Provider-level offers NOT present in catalogs from OTHER providers | POST `/beckn/discover` returning catalogs from different providers | Catalogs from other providers do NOT contain `offer-prov-${TS}` |
| SC-PO-12 | Provider-level offer enrichment runs AFTER pipeline filtering | Discover with schema context filter | `filterOffersByResourceIds` does NOT remove provider-level offers (they have no resourceIds to cross-check) |

### Verification

```bash
# Discover and check offers in response
curl -s http://localhost:8082/beckn/discover \
  -H 'Content-Type: application/json' \
  -d '{"context":{"action":"discover","messageId":"<uuid>","transactionId":"<uuid>","bapId":"bap-test","bapUri":"http://bap-test"},"message":{"intent":{"item":{"descriptor":{"name":"Widget"}}}}}' \
  | jq '.message.catalogs[].offers'
```

---

## Scenario Group 4: MERGE Mode Behavior

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-PO-13 | MERGE: new provider offer added, existing preserved | Publish with offer-A, then MERGE with offer-B | Both `offer-A` and `offer-B` in `provider_offer` table |
| SC-PO-14 | MERGE: idempotent replay updates payload | Publish offer-A with name "Original", replay with name "Updated" | `provider_offer` count = 1; payload contains "Updated" |
| SC-PO-15 | MERGE: `created_at` immutable on upsert | Publish, note `created_at`, replay | `created_at` unchanged; `updated_at` updated |

### SC-PO-13 verification

```bash
# After initial publish with offer-A
docker exec discovery-service-postgres psql -U catalog_user -d catalog_db -t -c \
  "SELECT count(*) FROM provider_offer WHERE catalog_id = '${CAT_ID}';"
# Expected: 1

# After MERGE publish with offer-B
docker exec discovery-service-postgres psql -U catalog_user -d catalog_db -t -c \
  "SELECT count(*) FROM provider_offer WHERE catalog_id = '${CAT_ID}';"
# Expected: 2
```

---

## Scenario Group 5: FULL Replace Mode Behavior

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-PO-16 | FULL replace: old provider offers deleted | Publish with offer-old, FULL replace with offer-new | `offer-old` gone from `provider_offer`; `offer-new` present |
| SC-PO-17 | FULL replace: resource-level offers also replaced | FULL replace with new resource-level offer | Old item offer gone, new item offer present in payload |
| SC-PO-18 | FULL replace: provider_offer count matches new publish only | FULL replace with 1 provider offer (was 3) | `provider_offer` count for catalog = 1 |

### SC-PO-16 verification

```bash
# publishDirectives for FULL replace
# "publishDirectives": [{"catalogId": "${CAT_ID}", "updateMode": "FULL"}]

docker exec discovery-service-postgres psql -U catalog_user -d catalog_db -t -c \
  "SELECT offer_id FROM provider_offer WHERE catalog_id = '${CAT_ID}';"
# Expected: only the new offer ID
```

---

## Scenario Group 6: Edge Cases

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-PO-19 | Missing `provider.id` on catalog → provider offers skipped | Publish catalog without `provider` field, with provider-level offer | No rows in `provider_offer`; offer silently ignored |
| SC-PO-20 | Null `provider.id` on catalog → provider offers skipped | Publish with `"provider": {"id": null}` | No rows in `provider_offer` |
| SC-PO-21 | Blank `provider.id` on catalog → provider offers skipped | Publish with `"provider": {"id": ""}` | No rows in `provider_offer` |
| SC-PO-22 | ALL offers are provider-level (no resource-level offers) | Publish with 3 provider offers, 0 resource-level offers | 3 rows in `provider_offer`; items have no offers in payload |
| SC-PO-23 | Offer-only catalog (no resources) with provider offers | Publish with `resources: []` and 2 provider offers | No item rows; 2 rows in `provider_offer` |
| SC-PO-24 | Multiple catalogs with same provider → offers scoped per catalog | Publish cat-A and cat-B both with provider prov-1, each with different offers | `provider_offer` has offers keyed by `(offer_id, catalog_id)` — no cross-catalog leakage |
| SC-PO-25 | Provider offer with `offerAttributes` preserved in payload | Publish with rich `offerAttributes` (discount, validFrom, etc.) | `provider_offer.payload` contains all attributes exactly as published |

### SC-PO-19 payload (no provider)

```json
{
  "context": { "...": "..." },
  "message": {
    "catalogs": [{
      "id": "cat-no-prov-${TS}",
      "resources": [{"id": "r1", "descriptor": {"name": "X"}, "resourceAttributes": {"@context": "https://schema.org/", "@type": "Product"}}],
      "offers": [
        {"id": "orphan-offer", "descriptor": {"name": "Orphan"}}
      ]
    }]
  }
}
```

---

## Scenario Group 7: Discovery Enrichment Edge Cases

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-PO-26 | No provider offers in DB → enricher is no-op | Discover for provider with no provider offers | `offers` array contains only resource-level offers (if any) |
| SC-PO-27 | Provider has offers but query returns no items from that provider | Discover with filter excluding the provider | No provider offers in response (enricher only runs for providers present in results) |
| SC-PO-28 | Multiple providers in results → each gets own provider offers | Discover returning catalogs from prov-1 and prov-2 | prov-1 catalogs have prov-1 offers only; prov-2 catalogs have prov-2 offers only |
| SC-PO-29 | Provider offer with malformed payload → skipped gracefully | Insert bad JSON in `provider_offer.payload` | Enricher logs WARN and skips; other offers still attached; no error to caller |
| SC-PO-30 | Provider offers preserved across catalog re-publish (MERGE) | Publish resources, then MERGE with new resources | Provider offers still returned at discover time for all resources from that provider |

---

## Scenario Group 8: ES + PostgreSQL Consistency

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-PO-31 | Provider offers NOT in ES document | ES search for resource | `_source` does NOT contain provider offers (they are enriched at query time, not indexed) |
| SC-PO-32 | PostgreSQL `provider_offer` table schema correct | `\d provider_offer` | Columns: `offer_id` (VARCHAR, PK part), `catalog_id` (VARCHAR, PK part), `provider_id` (VARCHAR), `payload` (TEXT/JSONB), `created_at` (TIMESTAMP), `updated_at` (TIMESTAMP) |

### ES verification

```bash
# ES document should NOT contain provider offer
curl -s "http://localhost:9200/beckn-catalog/_search" \
  -H 'Content-Type: application/json' \
  -d '{"query":{"term":{"resource_id":"R1-'${TS}'"}}}' \
  | jq '._source.offers // empty'
# Provider offer should NOT be here — only resource-level offers
```

---

## Scenario Group 9: Hostile User

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-PO-33 | Offer with `resourceIds: null` (explicit null) classified as provider-level | Publish with `"resourceIds": null` | Stored in `provider_offer` table, NOT stamped on items |
| SC-PO-34 | Very large provider offer payload (>100KB) | Publish with massive `offerAttributes` | Accepted and stored; no truncation |
| SC-PO-35 | Provider offer ID with special characters | Publish with `offer_id = "offer/special:chars-${TS}"` | Stored correctly; retrievable by ID |
| SC-PO-36 | Duplicate offer ID across catalogs | Publish `offer-dup` in cat-A and `offer-dup` in cat-B | Both stored (PK is `(offer_id, catalog_id)`) — no conflict |
| SC-PO-37 | 100+ provider offers in single catalog | Publish catalog with 100 provider offers | All 100 stored in `provider_offer`; all returned at discover time |

---

## Verification depth (after every provider-level offer test)

1. HTTP response: `{"status":"ACK"}` or HTTP 202
2. `provider_offer` table: correct `offer_id`, `catalog_id`, `provider_id`, `payload`, timestamps
3. Item payload: provider offer NOT stamped on items
4. Discover API: provider offers enriched at query time for all catalogs from that provider
5. ES: provider offers NOT in indexed documents
6. MERGE/FULL behavior: consistent with resource-level offer handling
7. Cross-provider isolation: offers only attached to matching provider's catalogs
