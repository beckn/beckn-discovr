---
id: "10"
name: "Discover query at scale (10k → 1M indexed)"
group: query
duration_min: 60
---

## Goal
Discover latency must stay flat as the index grows. Run the same query workload against indices of varying size and chart the scaling curve.

## Pre-condition
Index sizes prepared at 10k, 100k, and 1M items (each as a separate ES index or a swap-able dataset). The user must confirm which datasets are available before this scenario runs.

## Load shape
- JMeter script: `discover-scale.jmx`
- Concurrent users: 50
- Duration: 15 min per dataset × 4 dataset sizes
- Query: same mix across all sizes

## JMeter command
```
-Jusers=50 -JdurationPerSize=900 -JdatasetSizes=10000,50000,100000,1000000
```

## Metrics to capture
- ACK p99 per dataset size
- PG query p99 per dataset size
- ES query p99 per dataset size
- ES heap % per dataset size
- Result count distribution per dataset size

## SLOs
- ACK p99 ≤ 500 ms across all dataset sizes (allow 2× growth from 10k → 1M, then flat)
- ES heap ≤ 75 % at 1M items
- Result counts consistent with dataset size (no silent truncation)

## Notes
- This scenario is gated on dataset availability. If the user hasn't pre-staged the larger indices, mark SKIPPED and prompt before running.
