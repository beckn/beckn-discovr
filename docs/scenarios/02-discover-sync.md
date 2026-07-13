# 02 — Discover API — Synchronous (GET)

## Overview
Verify synchronous discover via GET. Covers text search, spatial search, JSONPath filters, combined queries, and validation error cases.

## Prerequisites
- Catalog from 01-catalog-ingestion SC-01 must be indexed (poll ES, max 30s)

## Scenarios

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-06 | Text search (matching keyword) | GET with `textSearch: "Verify Coffee"` | `context.action = "on_discover"`, `message.catalogs` array >= 1, uses `resources` field |
| SC-07 | Text search — response field validation | Same response as SC-06 | Each catalog: `id`, `descriptor.name`, `bppId`, `resources[].id`, `resources[].descriptor`. No `rateable: false`, no `ratingValue: 0` on items without ratings |
| SC-08 | Spatial search (s_dwithin near Bengaluru) | GET with spatial [77.5946, 12.9716], distanceMeters 5000 | `context.action = "on_discover"`, results include test item near MG Road |
| SC-09 | Spatial search (far away) | GET with [0.0, 0.0], distanceMeters 1000 | `message.catalogs` = empty array |
| SC-10 | Wrong action | GET with `"action": "wrong"` | NACK: `error.code = "SCH_SCHEMA_VALIDATION_FAILED"` |
| SC-11 | Missing transactionId | GET without `transactionId` | NACK (UUID validation fails) |
| SC-12 | Missing messageId | GET without `messageId` | NACK (only `transactionId` + `messageId` are required; `bapId` is optional and not validated) |
| SC-13 | Missing intent (empty) | GET with `message.intent: {}` | NACK (at least one search criterion) |
| SC-14 | Negative distanceMeters | GET with `distanceMeters: -1` | NACK (must be >= 0) |
| SC-15 | Invalid JSONPath expression | GET with `expression: "not-a-path"` | NACK (must start with $) |
| SC-15a | Valid JSONPath filter | GET with `expression: "$.catalogs[*].resources[?(@.resourceAttributes.category=='BEVERAGES')]"` | Results filtered to BEVERAGES category only |
| SC-15b | Combined: text + spatial | GET with `textSearch: "Coffee"` AND spatial | Results match both text AND location |
| SC-15c | Combined: text + JSONPath | GET with `textSearch: "Coffee"` AND JSONPath filter | Results match both criteria |

## Verification Depth

For every discover response:
1. Parse full JSON, check `context.action = "on_discover"`
2. `message.catalogs` is array (may be empty for no-match scenarios)
3. Field names: `resources` (not `items`), `resourceAttributes` (not `itemAttributes`)
4. No `@context`/`@type` on Resource or Descriptor — only on `resourceAttributes`
5. NACK responses: `message.status = "NACK"`, `error.code`, `error.message` present
