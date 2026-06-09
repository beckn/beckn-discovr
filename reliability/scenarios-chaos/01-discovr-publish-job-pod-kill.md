---
id: "D01"
name: "Discovr publish-job pod kill mid-push"
group: chaos-podkill
duration_min: 25
target: "catalog-publish-job deployment (Discovr side)"
chaos_action: "kubectl delete pod"
---

## Goal
Discovr `catalog-publish-job` consumes `catalog/push` events from Catalg, writes to Postgres, and indexes into Elasticsearch. A pod kill mid-message must not corrupt PG/ES consistency, must not drop the message, and must not double-index in ES.

## Pre-condition
- ≥ 2 Discovr publish-job replicas
- Steady-state lag ≈ 0 on the push-in Kafka consumer group
- ES cluster GREEN

## Background load
- JMeter script: `push-baseline.jmx` (drives Catalg, which pushes to Discovr) OR `discovr-direct-push.jmx` (if a direct push test endpoint exists)
- 20 concurrent users, 25 min
- Injection at minute 10

## Chaos action (one only)
```
kubectl delete pod -n <discovr-publish-namespace> <one-publish-job-pod> --grace-period=10
```

**Abort / recovery:**
```
kubectl rollout status deployment/catalog-publish-job -n <discovr-publish-namespace> --timeout=180s
```

## Per-action confirmation contract
- Target pod + node + uptime
- Current consumer lag on push-in topic
- ES cluster health, doc count for the test index
- PG row count in the items table for the test catalogs
- Kill command + abort command

WAIT.

## Metrics to capture (every 5 s, minute 9 to minute 18)
- Consumer lag on push-in topic
- Replica count + ready
- ES bulk index error rate
- ES bulk index latency p99
- PG insert error rate
- Discovr publish-job ERROR log count
- ES doc count delta (per minute)

## SLOs (recovery budget = 120 s)
- Lag returns to ≤ steady-state + 5 within 120 s
- Zero duplicate ES documents — `count(*) by _id` after run shows no duplicates for catalogs published during chaos window
- Zero orphan PG rows — every catalog row has matching items, every item has matching ES doc
- Zero new DLT entries attributable to the kill
- PG/ES consistency check passes: `pg.items.count_by_catalog == es.docs.count_by_catalog` for catalogs in chaos window
- No CrashLoopBackOff

## Cleanup verification
- All publish-job pods Ready
- ES still GREEN
- 5 sampled catalogs from chaos window: 1 row in `catalog_index` table + N rows in `items` table + N docs in ES, no duplicates
