---
id: "14"
name: "E2E push → searchable + discover → on_discover p99"
group: query
duration_min: 25
---

## Goal
Two end-to-end measurements in one run, because they share infrastructure:

1. **Push → searchable**: time from `/catalog/push` ACK to that catalog appearing in a `discover` query result.
2. **Discover → on_discover**: time from `discover` ACK to `on_discover` received at the BAP listener.

These two numbers, together, are the "network user-facing" latency.

## Pre-condition
- Healthy external enrichment API (`fast-200` mode)
- Index has baseline data (≥ 100k items)

## Load shape
- JMeter script: `e2e-push-to-discover.jmx`
- Two thread groups:
  - 30 push users (each push tags catalogs with a unique correlation field that JMeter then queries for)
  - 30 discover users (queries include the recently-pushed correlation field)
- Duration: 25 min
- JMeter listens on the configured port for `on_discover` callbacks

## JMeter command
```
-JpushUsers=30 -JdiscoverUsers=30 -Jduration=1500 -JcallbackListenerPort=8888 -JexternalApiBehavior=fast-200
```

## Metrics to capture
- **Push → searchable latency** per push: timestamp_push_ack → timestamp_first_discover_returning_this_catalog. Sampled per push (e.g. JMeter polls a discover query for 10 s after each push until the catalog appears, recording the delay).
- **Discover → on_discover latency** per discover: timestamp_discover_ack → timestamp_callback_received
- Per-stage span timings from ClickStack (publish-job persistence → ES index → discover-job query → external API → dispatcher POST)

## SLOs
- Push → searchable p99 ≤ 2000 ms
- Discover → on_discover p99 ≤ 3000 ms
- Zero message loss — every push eventually becomes searchable; every discover eventually yields an on_discover (within a generous deadline like 60 s)
- Trace context (`transactionId`, `messageId`) propagated end-to-end — sample 10 traces and verify

## Notes
- This is the headline number for the Discovr reliability suite. Put it on the report front page.
- If `push → searchable` is high, the bottleneck is in indexing — point at scenarios 02/06.
- If `discover → on_discover` is high, the bottleneck is in the async pipeline or external API — point at scenarios 08/11/12.
