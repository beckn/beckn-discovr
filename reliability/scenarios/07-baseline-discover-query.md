---
id: "07"
name: "Baseline discover query"
group: query
duration_min: 5
---

## Goal
Establish single-user latency floor for `POST /beckn/discover` and confirm the query path (PG / PostGIS + ES) is healthy. Gate scenario for the query suite.

## Pre-condition
Index populated with ≥ 1000 catalogs / 50 000 items. Run scenario 01 + 02 first if fresh.

## Load shape
- JMeter script: `discover-baseline.jmx`
- Concurrent users: 1
- Duration: 5 min
- Query: simple text + single network filter

## JMeter command
```
-Jusers=1 -Jduration=300 -JqueryComplexity=simple
```

## Metrics to capture
| Metric | Source | Filter |
|---|---|---|
| HTTP ACK latency p50 / p95 / p99 | ClickStack | `service.name=catalog-discover-job` `http.route=/beckn/discover` |
| HTTP 2xx / 4xx / 5xx | ClickStack | same |
| PG query p99 | ClickStack | `service.name=catalog-discover-job` PG span |
| ES query p99 | ClickStack | ES span |

## SLOs
- ACK error rate ≤ 0.1 %
- ACK p99 ≤ 300 ms (ACK is synchronous; the heavy work is async)
- No 5xx
- ACK returned for every request

## Notes
- This scenario does NOT wait for `on_discover` — that's scenario 11. This is ACK-only.
