# 04 — MERGE Mode

## Overview
Verify default MERGE behavior: upserts incoming resources, preserves existing resources not in the incoming set, and propagates offer updates to linked items.

## Prerequisites
- Catalog from 01-catalog-ingestion SC-01 must be indexed

## Scenarios

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-18 | MERGE: add R4, update OFFER-1 discount to 25% | POST push same catalog with R4 + updated OFFER-1 (no `updateMode`) | HTTP 200 ACK |
| SC-19 | DB: 4 resources after MERGE | `SELECT id FROM item WHERE catalog_id = '${CAT_ID}'` | 4 rows (R1, R2, R3 preserved + R4 added) |
| SC-20 | DB: offer propagated to R1 and R2 | `SELECT payload FROM item WHERE id = 'R1-${TS}' AND catalog_id = '${CAT_ID}'` | Payload JSON has offer with `discount: "25%"` |
| SC-21 | Logs show MERGE mode | `docker logs discovr-ingestion` | `event=persist.completed` with `mode=MERGE inserted=1 updated=` |

## Verification Depth

- Verify old resources (R1, R2, R3) still present in DB with unchanged `id` (there is no `bpp_id` column — BPP identity lives inside the `payload` JSON)
- Verify R4 was inserted (new row)
- Verify offer `OFFER-1` discount changed in R1 and R2 payloads (offer propagation Phase 2)
- Verify `discovr.publish.persist.inserted` metric incremented
- Verify `discovr.publish.merge` metric incremented
