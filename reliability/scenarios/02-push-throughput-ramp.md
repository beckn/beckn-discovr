---
id: "02"
name: "Push throughput ramp"
group: indexing
duration_min: 45
---

## Goal
Find the push-ingest knee — the concurrency at which `/catalog/push` latency or error rate breaks budget, or where PG / ES write lag stops draining.

## Load shape
- JMeter script: `push-ramp.jmx` (stepping thread group)
- Steps: 10 → 50 → 100 → 250 → 500 concurrent, 8 min per step + 1 min ramp
- Payload: 2 catalogs × 50 resources + 10 offers, MERGE

## JMeter command
```
-Jsteps=10,50,100,250,500 -JstepDurationSec=480 -JrampSec=60 -JupdateMode=MERGE
```

## Metrics to capture
- HTTP p50/p95/p99 per step
- HTTP error rate per step
- ES bulk indexer queue size + reject count per step
- ES indexing latency p99 per step
- PG write latency p99 (HikariCP + statement-level)
- catalog-publish-job JVM heap %
- PG connection pool used %

## SLOs
- p99 ≤ 500 ms sustained at the highest step where error rate ≤ 0.1 %
- Knee identified: first step where any of {p99 > 500 ms, error rate > 0.1 %, ES bulk reject > 0, ES queue full}
- No OOM, no pod restarts

## Notes
- Knee feeds into the load level used for soak (scenario 03) and mixed-load (scenario 09).
