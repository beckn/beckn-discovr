---
name: e2e
description: Quick E2E smoke test across the full Beckn stack (Catalg + Discovr). Publishes, subscribes, discovers, pulls, checks DB — reports pass/fail in 30 seconds.
user_invocable: true
---

Run a quick E2E smoke test against the live Docker stack. Use unique IDs per run to avoid conflicts.

## Steps

1. Check all containers are running
2. Publish a test catalog (3 resources + 2 offers, no @context/@type on core objects, only on resourceAttributes/offerAttributes)
3. Subscribe to the test network
4. Wait 15s for indexing
5. Discover by text search (include networkId and schemaContext: [] in context)
6. Pull FULL mode
7. Check DB (items persisted, subscription status)
8. Delete subscription
9. Report pass/fail table

## Payload rules
- NO @context/@type on Resource, Offer, Descriptor, Location, TimePeriod
- @context/@type ONLY on resourceAttributes and offerAttributes
- Provider on offers MUST include both id and descriptor
- Discover context MUST include networkId and schemaContext: []
- Subscription action: catalog/subscription / catalog/on_subscription
- Subscription path: /beckn/catalog/subscription
- Use network: retail-grocery (ensure it exists in DB first)

## Services
- Catalog API: http://localhost:3000
- Catalog Indexer: http://localhost:8084
- Catalog Publish Job: http://localhost:8085
- Discover Job: http://localhost:8082
- Catalg Postgres: docker exec catalog-service-postgres psql -U catalog_user -d catalog_db
- Discovr Postgres: docker exec discovery-service-postgres psql -U catalog_user -d catalog_db

## Report format
```
| # | Test | Result |
|---|------|--------|
| 1 | Publish | ACK/NACK |
| 2 | Subscribe | action/FAIL |
| 3 | Discover | catalogs/resources count |
| 4 | Pull | catalogs count |
| 5 | DB items | count |
| 6 | DB subscription delete | INACTIVE |
```
