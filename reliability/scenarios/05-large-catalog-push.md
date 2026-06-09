---
id: "05"
name: "Large catalog push payload sweep"
group: indexing
duration_min: 15
---

## Goal
Quantify payload-size impact on ingest. Establish the maximum reasonable catalog size before ES bulk indexer queues fill or PG write latency spikes.

## Load shape
- JMeter script: `push-large.jmx`
- Concurrent users: 20
- Duration: 15 min
- Resource counts sweep: 50, 200, 500, 1000 per catalog (5 catalogs per sweep)

## JMeter command
```
-Jusers=20 -Jduration=900 -JresourceCountSweep=50,200,500,1000
```

## Metrics to capture
- HTTP p99 per payload size
- catalog-publish-job JSON parse time
- Per-resource processing time
- ES bulk indexer batch size + latency
- PG batch insert latency
- Request size warnings in logs

## SLOs
- 50 resources: p99 ≤ 500 ms
- 200 resources: p99 ≤ 1500 ms
- 500 resources: p99 ≤ 4000 ms
- 1000 resources: completes without OOM, without exceeding any size limits
- No ES `circuit_breaking_exception`
- No PG `out of memory` errors
