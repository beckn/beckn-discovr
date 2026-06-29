---
id: "12"
name: "on_discover with slow / failing external API"
group: query
duration_min: 30
---

## Goal
When the external enrichment API misbehaves, `on_discover` must remain bounded — either time out cleanly and proceed with a fallback, or fail fast without exhausting the dispatcher thread pool. Verify circuit-breaker / timeout / retry behavior.

## Pre-condition
- `external_enrichment.mock_url` points at a controllable mock that can return any of:
  - `slow-2s` — 200 OK after 2 s
  - `slow-10s` — 200 OK after 10 s (longer than `external_enrichment.timeout_ms`)
  - `503-flaky` — returns 503 50 % of the time
  - `500-always` — always 500
- The mock URL is configurable per phase via a header or query parameter so the agent does not need to write to anything.

## Load shape
- JMeter script: `discover-bad-external.jmx`
- Concurrent users: 30
- Duration: 30 min total, 4 phases × 7 min each cycling the external behaviors above

## JMeter command
```
-Jusers=30 -JphaseDurationSec=420 -JexternalBehaviorSeq=slow-2s,slow-10s,503-flaky,500-always
```

## Metrics to capture per phase
- External call latency p99
- External call timeout rate
- on_discover delivery success rate
- on_discover fallback path invoked count (if the design has one)
- response-dispatcher thread pool active count
- response-dispatcher retry count
- response-dispatcher DLT count

## SLOs
- `slow-2s` phase: on_discover success rate ≥ 99 % (within `external.timeout_ms`)
- `slow-10s` phase: on_discover either (a) returns fallback within timeout + 500 ms, or (b) gracefully reports failure — either way, response-dispatcher thread pool does not pin
- `503-flaky` phase: retry logic kicks in; eventual success rate ≥ 95 %
- `500-always` phase: clean failure path — no retry storm, no thread pool exhaustion, errors reach DLT not the main log as ERROR
- Thread pool active ≤ 80 % of capacity in every phase

## Notes
- This is the most important "bad day" scenario. If on_discover blocks indefinitely, every dispatcher slot will eventually be consumed and the network goes silent.
