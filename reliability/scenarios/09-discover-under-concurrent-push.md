---
id: "09"
name: "Discover under concurrent push (mixed load)"
group: query
duration_min: 30
---

## Goal
Verify query latency stays in budget while ingest is running at sustained load. Catches PG/ES lock contention and ES refresh interval interactions.

## Load shape
- Two JMeter thread groups in one run:
  - 50 users pushing (MERGE)
  - 50 users discovering (mixed queries)
- Duration: 30 min

## JMeter command
```
-JpushUsers=50 -JdiscoverUsers=50 -Jduration=1800
```

## Metrics to capture
- Push p99 + discover ACK p99 (both in same chart)
- ES refresh count + refresh latency
- ES segment count growth
- PG lock wait time
- catalog-publish-job + catalog-discover-job CPU + heap

## SLOs
- Push p99 ≤ 500 ms (no worse than scenario 02 at same step)
- Discover ACK p99 ≤ 300 ms (no worse than scenario 07)
- ES refresh latency stable (not growing)
- No deadlocks
- No ES `index.refresh_interval` warnings
