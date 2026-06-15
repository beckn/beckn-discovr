---
id: "04"
name: "Concurrent same-catalogId push"
group: indexing
duration_min: 10
---

## Goal
Multiple pushes target the same `catalogId` concurrently. Verify last-writer-wins, no row corruption in PG, no document divergence between PG and ES.

## Load shape
- JMeter script: `push-concurrent-same-id.jmx`
- Concurrent users: 20 — all hitting the same 3 `catalogId`s
- Duration: 10 min
- Payload: 2 catalogs × 50 resources, MERGE

## JMeter command
```
-Jusers=20 -Jduration=600 -JcatalogIdPool=3
```

## Metrics to capture
- HTTP p99 + error rate
- ES bulk indexer version-conflict count (should be zero or recoverable)
- Post-run `item_index` row count per catalogId — must equal most recent push's resource count
- Post-run ES doc count per catalogId — must match PG row count
- PG advisory-lock wait time (if used)

## SLOs
- Zero unrecoverable ES version conflicts
- PG row count == ES doc count per catalogId (consistency)
- No duplicate `(item_id, catalog_id)` rows
- p99 ≤ 700 ms (write contention raises the floor)
