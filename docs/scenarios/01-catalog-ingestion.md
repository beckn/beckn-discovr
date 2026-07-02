# 01 — Catalog Ingestion (Push → Index)

## Overview
Verify that catalogs pushed to the Discovr push endpoint are persisted in PostgreSQL and indexed in Elasticsearch.

## Scenarios

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-01 | Push valid catalog (3 resources + 1 offer) | POST `http://localhost:8085/catalog/push` | HTTP 200 `{"message":{"status":"ACK","messageId":"..."}}` |
| SC-02 | Push with context missing both `messageId` and `transactionId` | POST with `context` present but no correlation id | HTTP 400 NACK: `error.code = "CTX_MISSING_FIELD"` |
| SC-03 | Push with unparseable JSON body | POST with malformed JSON | HTTP 400 NACK: `error.code = "SCH_INVALID_JSON"` |
| SC-04 | Push with missing context entirely | POST without `context` object | HTTP 400 NACK: `error.code = "CTX_MISSING_FIELD"` |
| SC-05 | Push payload too large | POST with body exceeding `maxPayloadSize` | HTTP 400 NACK: `error.code = "SCH_SCHEMA_VALIDATION_FAILED"` (413 is not part of the Beckn response set) |

> Note: `catalog/push` validates only that a `context` object is present **and** carries at least one correlation id (`messageId` or `transactionId`). `bppId`/`bppUri` are **not** validated at the push boundary.

## Verification Depth

After SC-01:
- **DB**: `SELECT id, catalog_id, type, network_id FROM item WHERE catalog_id = '${CAT_ID}'` → 3 rows (PK is `(id, catalog_id)`; there is no `bpp_id` or `name` column — BPP identity lives inside the `payload` JSON only)
- **DB**: `SELECT payload->'context' FROM item WHERE catalog_id = '${CAT_ID}'` → BPP identity resolvable from the `payload` JSON
- **ES**: `curl -s 'http://localhost:9200/beckn-catalog-*/_search?q=catalog_id:${CAT_ID}'` → `hits.total.value` = 3
- **ES**: Each document has `catalog_id`, `resource_id`, `resource_name`, `catalog_bpp_id` (BPP is catalog-level — there is no `bpp_id` ES field), `schema_type`; doc id = `${CAT_ID}:${RESOURCE_ID}`
- **Logs**: `docker logs discovr-ingestion` → `event=persist.completed catalogId=${CAT_ID} mode=MERGE resources=3` (logs resource count, not "items")
- **No publishDirectives in DB**: payload JSON does NOT contain `publishDirectives`
