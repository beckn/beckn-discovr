---
id: "11"
name: "on_discover async callback success throughput"
group: query
duration_min: 20
---

## Goal
`on_discover` is async — Discovr ACKs `discover` synchronously, then queries, invokes the **external enrichment API**, and POSTs `on_discover` to the calling BAP via `response-dispatcher`. Verify the full async path holds under sustained discover load with a healthy external API.

## Pre-condition
- Index populated (≥ 100k items)
- `external_enrichment.url` configured to point at a healthy mock that returns 200 within 200 ms

## Load shape
- JMeter script: `discover-async-success.jmx`
- Concurrent users: 50
- Duration: 20 min
- JMeter exposes a callback listener on a configured port; counts `on_discover` callbacks received

## JMeter command
```
-Jusers=50 -Jduration=1200 -JcallbackListenerPort=8888 -JexternalApiBehavior=fast-200
```

## Metrics to capture
- ACK p99 (synchronous)
- E2E p50 / p95 / p99: `discover` ACK → `on_discover` received at JMeter listener
- External enrichment call p99 (separate metric — segment by the external HTTP call)
- response-dispatcher consumer lag
- response-dispatcher HTTP POST success rate
- on_discover count == discover count (no message loss)

## SLOs
- E2E p99 ≤ 3000 ms
- on_discover success rate ≥ 99.9 %
- Zero "callback failed" errors in `response-dispatcher` logs (apart from the allowed 1-in-1000 transient retry)
- All callbacks carry the original `transactionId` + `messageId` (sample 10 and verify)
