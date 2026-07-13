# 07 — Full Pipeline (Catalg → Discovr)

## Overview
Verify the complete pipeline: Subscribe via Catalg → Publish via Catalg → Indexer → Evaluator → Delivery → Discovr push → DB + ES indexing → Discover API.

**Skip ALL if Catalg stack is not running.**

## Scenarios

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-32 | Subscribe via Catalg API | POST `http://localhost:3000/catalog/subscription` | `status = "ACTIVE"` |
| SC-33 | Publish via Catalg API | POST `http://localhost:3000/catalog/publish` | `{"status":"ACK"}` |
| SC-34 | Wait for pipeline completion | Poll Discovr postgres for items (max 60s) | Items from SC-33 appear in Discovr DB |
| SC-35 | Discover catalog from full pipeline | GET discover with matching textSearch | `message.catalogs` includes the SC-33 catalog |

## Verification Depth

- SC-32: Verify `context.action = "catalog/on_subscription"`, `message.subscriptions` array
- SC-33: Verify `{"status":"ACK"}` — no extra fields
- SC-34: Poll `SELECT count(*) FROM item WHERE payload::text LIKE '%pipeline-verify%'` until > 0 (there is no `bpp_id` column — BPP identity lives inside the `payload` JSON / `context_url`)
- SC-35: Verify discover response has correct `resources` field structure, catalog metadata
- Check delivery logs: `docker logs catalog-delivery-job` for callback attempt (Catalg)
- Check Discovr ingestion logs: `docker logs discovr-ingestion` for ingestion
