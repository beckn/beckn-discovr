---
description: >
  Seed a Discovr-friendly dataset by verifying Catalg delivery to Discovr /catalog/push and then exercising
  Discovr discover GET/POST queries. This is complementary to Catalg-side seeding.
---

Use this skill when your goal is: **get data into Discovr and confirm discover works**.

## Recommended workflow

1. Run the Catalg skill `/seed-grocery-master-regular` first (in the Catalg repo). That will:
   - create subscription with callback = this Discovr stack’s `/catalog/push`
   - publish master + regular catalogs

2. Then run the Discovr checks below.

## Step 1 — Verify ingestion endpoint is up

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8085/catalog/push
```

Expected: typically `405` (GET not allowed) or `404` depending on routing, but container should be reachable.

Verify logs show successful callbacks during the seed:
- `docker logs catalog-publish-job --since=10m --tail=200 2>&1 | grep -E "catalog.push.received|rejected.oversized"`

## Step 2 — Discovr Discover GET (sync)

Use a keyword known to exist in the ingested grocery data.

```bash
curl -s -X GET http://localhost:8082/beckn/discover \
  -H "Content-Type: application/json" \
  -d '{
    "context": {
      "version": "2.0.0",
      "action": "discover",
      "timestamp": "2026-03-18T00:00:00Z",
      "messageId": "26000000-0000-0000-0000-000000000011",
      "transactionId": "26000000-0000-0000-0000-000000000012",
      "bapId": "test-bap.local",
      "bapUri": "http://test-bap.local",
      "networkId": "ondc-retail-grocery",
      "schemaContext": "<GROCERY_SCHEMA_CONTEXT>"
    },
    "message": { "intent": { "item": { "descriptor": { "name": "<KEYWORD_FROM_DATA>" } } } }
  }'
```

Expected: HTTP `200` with matching catalogs/items.

## Step 3 — Discovr Discover POST (async)

```bash
curl -s -X POST http://localhost:8082/beckn/discover \
  -H "Content-Type: application/json" \
  -d '{
    "context": {
      "version": "2.0.0",
      "action": "discover",
      "timestamp": "2026-03-18T00:00:00Z",
      "messageId": "26000000-0000-0000-0000-000000000013",
      "transactionId": "26000000-0000-0000-0000-000000000014",
      "bapId": "test-bap.local",
      "bapUri": "http://test-bap.local",
      "networkId": "ondc-retail-grocery",
      "schemaContext": "<GROCERY_SCHEMA_CONTEXT>"
    },
    "message": { "intent": { "item": { "descriptor": { "name": "<KEYWORD_FROM_DATA>" } } } }
  }'
```

Expected: HTTP `200` ACK.

Verify queueing + dispatcher:
- `docker logs catalog-discover-job --since=5m --tail=200 2>&1 | grep -E "Queued async discovery request|Schema validation passed"`
- `docker logs response-dispatcher --since=5m --tail=200 2>&1`

