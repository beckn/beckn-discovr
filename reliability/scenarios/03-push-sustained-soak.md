---
id: "03"
name: "Push sustained soak — 4 h"
group: soak
duration_min: 240
---

## Goal
Detect slow degradation on the ingest path: memory leaks (publish + dispatcher), GC drift, HikariCP connection leaks, ES heap pressure, ES translog growth, disk usage trajectory.

## Load shape
- JMeter script: `push-soak.jmx`
- Concurrent users: 100 (or 80 % of knee from scenario 02 — pick the smaller)
- Duration: 4 hours

## JMeter command
```
-Jusers=100 -Jduration=14400 -JupdateMode=MERGE
```

## Metrics to capture (every 60 s)
- HTTP p50/p95/p99 — hourly slope
- HTTP error rate slope
- catalog-publish-job JVM heap % — slope per hour
- ES heap % — slope per hour
- ES JVM old-gen pressure
- ES translog size
- ES bulk reject count over time
- PG connection pool used %
- PG WAL growth
- PG table sizes (`pg_total_relation_size`)
- Pod RSS, FD count

## SLOs
- Heap drift ≤ 5 % per hour after first 30 min warmup (catalog-publish-job + ES)
- p99 latency drift ≤ 10 % from hour 1 to hour 4
- ES bulk reject count = 0 sustained
- Zero pod restarts
- Zero OOM
- Connection pool used % ≤ 80 %

## Notes
- Most important indexing scenario. Run overnight if needed.
- Hourly summary table attached to report (hour 1, 2, 3, 4 values for every metric).
- Pod restart → FAIL.
