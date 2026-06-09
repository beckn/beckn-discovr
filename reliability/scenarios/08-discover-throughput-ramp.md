---
id: "08"
name: "Discover throughput ramp"
group: query
duration_min: 45
---

## Goal
Find the query knee — the concurrency at which discover ACK latency or async pipeline lag breaks budget.

## Load shape
- JMeter script: `discover-ramp.jmx`
- Steps: 10 → 50 → 100 → 250 → 500 concurrent, 8 min per step + 1 min ramp
- Query: mixed (text-only, network-filter, geo-filter, multi-filter)

## JMeter command
```
-Jsteps=10,50,100,250,500 -JstepDurationSec=480 -JrampSec=60 -JqueryMix=text,network,geo,multi
```

## Metrics to capture
- ACK p50/p95/p99 per step
- ACK error rate per step
- Discover-events Kafka topic lag (consumer_group=catalog-discover-job)
- PG query p99 + ES query p99 per step
- catalog-discover-job JVM heap %
- response-dispatcher consumer lag

## SLOs
- ACK p99 ≤ 300 ms sustained at the highest step where error rate ≤ 0.1 %
- Discover-events lag drains between steps
- No OOM, no pod restarts
- Knee identified — feeds into mixed-load scenario 09

## Notes
- Async pipeline lag is the most important secondary signal here. ACK can stay fast while the downstream stalls.
