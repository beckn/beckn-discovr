---
id: "13"
name: "on_discover async callback soak — 4 h"
group: soak
duration_min: 240
---

## Goal
Detect slow degradation on the async query / dispatch path: response-dispatcher heap drift, HTTP connection pool growth, retry / DLT accumulation, log error rate slope.

## Pre-condition
- Healthy external enrichment API (`fast-200` mode)
- Index populated

## Load shape
- JMeter script: `discover-async-soak.jmx`
- Concurrent users: 30
- Duration: 4 hours

## JMeter command
```
-Jusers=30 -Jduration=14400 -JcallbackListenerPort=8888 -JexternalApiBehavior=fast-200
```

## Metrics to capture (every 60 s)
- E2E p99 — hourly slope
- response-dispatcher JVM heap % — hourly slope
- response-dispatcher HTTP connection pool active + idle
- response-dispatcher DLT message count
- ERROR log rate per minute
- Open file descriptor count

## SLOs
- E2E p99 drift ≤ 10 % from hour 1 to hour 4
- response-dispatcher heap drift ≤ 5 % per hour after warmup
- DLT growth rate stable (no acceleration)
- ERROR log rate ≤ 10 / min sustained
- Zero pod restarts
