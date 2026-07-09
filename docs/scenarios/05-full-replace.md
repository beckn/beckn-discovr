# 05 — FULL Replace Mode

## Overview
Verify `publishDirectives` with `updateMode: "FULL"` deletes all existing items + locations + ES docs for the catalog (scoped by `catalog_id` only — there is no BPP scoping), then inserts only the incoming resources.

## Scenarios

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-22 | Publish catalog with 3 resources | POST push with new `FULL_CAT_ID`, 3 resources | HTTP 200 ACK |
| SC-23 | Verify 3 resources in DB | DB query | 3 rows for `FULL_CAT_ID` |
| SC-24 | FULL replace with only 1 resource | POST push same `FULL_CAT_ID` with `publishDirectives: [{"catalogId":"${FULL_CAT_ID}","updateMode":"FULL"}]` (array keyed by catalogId — an object form is ignored and defaults to MERGE) and 1 resource | HTTP 200 ACK |
| SC-25 | DB: only 1 resource (old 2 gone) | `SELECT id FROM item WHERE catalog_id = '${FULL_CAT_ID}'` | Exactly 1 row |
| SC-25a | ES: old docs cleaned | `curl -s 'http://localhost:9200/beckn-catalog-*/_search?q=catalog_id:${FULL_CAT_ID}'` | `hits.total.value` = 1 |
| SC-25b | Logs: FULL replace delete counts | `docker logs discovr-ingestion` | `event=full.replace.deleted deletedItems=3 deletedLocations=` |
| SC-25c | Logs: mode=FULL at persist completion | `docker logs discovr-ingestion` | `event=persist.completed mode=FULL` |
| SC-25d | publishDirectives NOT in DB | `SELECT payload FROM item WHERE catalog_id = '${FULL_CAT_ID}' LIMIT 1` | Payload JSON does NOT contain `publishDirectives` |

## Verification Depth

- Delete order: locations deleted BEFORE items (FK dependency)
- ES deleteByQuery runs AFTER DB transaction commits
- Logs show `full.replace.es.deleted deletedDocs=N`
- Metrics: `discovr.publish.full.replace` incremented
- Metrics: `discovr.publish.full.replace.deleted.resources` shows correct count (Prometheus: `discovr_publish_full_replace_deleted_resources_total`)
