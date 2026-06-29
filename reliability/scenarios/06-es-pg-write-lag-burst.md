---
id: "06"
name: "ES + PG write lag under push burst"
group: indexing
duration_min: 20
---

## Goal
A burst of pushes should not push PG write latency or ES indexing lag unbounded. Verify recovery within budget.

## Load shape
- JMeter script: `push-burst.jmx`
- Burst pattern: 500 RPS for 2 min, idle for 3 min, repeat 4×
- Duration: 20 min

## JMeter command
```
-JburstUsers=500 -JburstDurationSec=120 -JidleSec=180 -Jcycles=4
```

## Metrics to capture (every 15 s)
- ES indexing latency p99 — peak and time-to-drain
- ES bulk queue size
- ES bulk reject count
- PG write latency p99
- PG WAL writer activity
- catalog-publish-job CPU + heap during burst

## SLOs
- ES indexing latency p99 returns to ≤ 500 ms within 60 s of burst end
- ES bulk reject count = 0 (queue must absorb the burst or backpressure cleanly)
- PG write latency p99 ≤ 200 ms in steady state, returns within 60 s
- No `OutOfMemoryError`
- No consumer group rebalance (if Kafka-driven)
