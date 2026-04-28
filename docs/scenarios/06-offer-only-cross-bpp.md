# 06 — Offer-Only Push + Cross-BPP Resolution

## Overview
Verify that offers referencing resources from another BPP are resolved correctly via Phase 3 (OfferResolutionStep). No stub rows, BPP identity preserved, ES re-indexed.

## Prerequisites
- Resources from 01-catalog-ingestion SC-01 must exist in DB

## Scenarios

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-26 | Push offer-only catalog (0 resources, 2 offers referencing R1 and R2) from different BPP | POST push | HTTP 202 ACK |
| SC-27 | No stub rows created | `SELECT count(*) FROM item WHERE id = 'R1-${TS}'` | Exactly 1 row (not 2) |
| SC-28 | Offer attached to existing item | `SELECT payload FROM item WHERE id = 'R1-${TS}'` | Payload JSON contains the cross-BPP offer |
| SC-29 | BPP identity preserved | `SELECT bpp_id FROM item WHERE id = 'R1-${TS}'` | `bpp_id` = original publisher BPP (NOT offer publisher) |
| SC-30 | ES re-indexed with attached offer | ES search for `resource_id:R1-${TS}` | `_source.offers` contains the cross-BPP offer |
| SC-31 | Discover API returns resource with offer | GET discover matching R1 name | Response includes the cross-BPP offer |

## Verification Depth

- Minimal resources (no descriptor) must be skipped by `isRealResource()` — no garbage rows
- `offer_ids` column on item updated with the new offer ID
- Logs: `event=offer.resolve.completed` with itemId and offersAttached count
- Metrics: `discovr.publish.offer.resolve.success` incremented
- contextNode from publishing BPP does NOT leak into the target item's context
