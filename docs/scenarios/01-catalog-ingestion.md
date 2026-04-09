# 01 — Catalog Ingestion (Push → Index)

## Overview
Verify that catalogs pushed to the Discovr push endpoint are persisted in PostgreSQL and indexed in Elasticsearch.

## Scenarios

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-01 | Push valid catalog (3 resources + 1 offer) | POST `http://localhost:8085/catalog/push` | HTTP 202 `{"status":"ACK"}` |
| SC-02 | Push with missing bppId in context | POST without `context.bppId` | HTTP 400 NACK: `errorCode = "INVALID_REQUEST"`, `errorMessage` mentions bppId |
| SC-03 | Push with missing bppUri in context | POST without `context.bppUri` | HTTP 400 NACK |
| SC-04 | Push with missing context entirely | POST without `context` object | HTTP 400 NACK |
| SC-05 | Push payload too large | POST with body exceeding `maxPayloadSize` | HTTP 413 Payload Too Large |

## Verification Depth

After SC-01:
- **DB**: `SELECT id, catalog_id, bpp_id, name FROM item WHERE catalog_id = '${CAT_ID}'` → 3 rows
- **DB**: `SELECT id FROM item WHERE catalog_id = '${CAT_ID}' AND bpp_id = '${BPP_ID}'` → 3 rows (composite PK)
- **ES**: `curl -s 'http://localhost:9200/beckn-catalog-*/_search?q=catalog_id:${CAT_ID}'` → `hits.total.value` = 3
- **ES**: Each document has `catalog_id`, `resource_id`, `resource_name`, `bpp_id`, `schema_type`
- **Logs**: `docker logs catalog-publish` → `event=persist.completed catalogId=${CAT_ID} mode=MERGE items=3`
- **No publishDirectives in DB**: payload JSON does NOT contain `publishDirectives`
