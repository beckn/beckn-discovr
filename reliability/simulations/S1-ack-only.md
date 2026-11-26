---
id: "S1"
name: "ACK-only (auth + schema + Kafka produce)"
entry: "POST /beckn/discover"
exit: "synchronous ACK/NACK HTTP response"
profiles: ["1M", "15M", "100M-subset"]
slo_file: "reliability/config/slos-100m.yaml"
driver: "jmx/s1-ack-only.jmx"
duration_min: 40
---

## Goal

Prove discover **ACK p99** under contractual TPM / peak without waiting on the async
query → `on_discover` path. Measures Tomcat/thread pool, Beckn auth (+ registry cache
hit/miss), schema validation, and Kafka request-topic produce.

## Entry / exit

| | |
|--|--|
| **Entry** | `POST /beckn/discover` (auth + schema + Kafka produce) |
| **Exit** | Synchronous ACK/NACK HTTP response |
| **Not measured** | Engine query, dispatcher, callback delivery |

## Infra

- `catalog-discover-job` + Kafka + **mocked registry**
- **Consumers paused** so query backlog does not pollute ACK timings:
  - Prefer `DISCOVERY_KAFKA_LISTENER_ENABLED=false` or Spring profile `sim-s1`
  - Or scale discover consumer replicas / pause listener (see `scripts/pause-discover-consumers.sh`)
- Response / dispatcher path may be ignored for this sim

## Pre-condition

1. `reliability/config/cluster.yaml` filled (same guardrails as Phase-1)
2. Discover consumers paused (`simulation.s1.consumer_paused: true` after pause)
3. Registry mock URL in `cluster.yaml` → `simulation.registry_mock_url`
4. Mock mode set: `cache-hit` | `cache-miss` | `registry-down` (see `scripts/mock-registry.sh`)
5. JMeter VM has `s1-ack-only.jmx` synced (`sim-run.sh` copies from `reliability/jmx/`)

## Load shape

| Step | Target QPS | Sustain |
|------|------------|---------|
| 1 | 200 (~12k TPM) | 10–20 min |
| 2 | 600 (×3 peak) | 10–20 min |
| 3 (optional) | 1k–2k | 5–10 min |

Path mix is irrelevant to the exit metric; bodies stay valid discover intents.
Default template: cheap T (`fixtures/intents/intent-t.json`). Optional J template also shipped.

## JMeter

```bash
./reliability/scripts/sim-run.sh S1 \
  --profile 15M \
  --tpm 12000 \
  --registry-mode cache-hit

./reliability/scripts/sim-run.sh S1 \
  --profile 15M \
  --tpm 12000 \
  --registry-mode cache-miss
```

Equivalent direct trigger (after sync):

```text
jmeter-trigger.sh S1 s1-ack-only.jmx \
  -Jusers=<N> -Jduration=<sec> -Jramp=<sec> \
  -JdiscoverUrl=<endpoints.discovr_discover> \
  -JintentFile=intent-t.json \
  -JregistryMode=cache-hit
```

No callback listener — HTTP sample timers only.

## Metrics to capture

- ACK p50 / p95 / p99 (HTTP sample)
- ACK error rate (non-2xx / NACK), excluding intentional `registry-down`
- Kafka produce p99 (ClickStack / Micrometer `discovery.publishing.*` if available)
- Tomcat thread / reject signals
- Auth cache dimension: `ack.auth.cache=hit|miss` when labeled

## SLOs (`slos-100m.yaml`)

| Metric | Pass |
|--------|------|
| ACK p99 cache-hit | ≤ **100 ms** |
| ACK p99 cache-miss | ≤ **250 ms** |
| ACK error rate | ≤ **0.1%** |
| Kafka produce p99 | ≤ **20 ms** |

## Fail fast

- ACK p99 > 250 ms on miss mode
- Error rate > 0.1% (healthy modes)
- Kafka produce errors
- Thread pool exhaustion / accept-count saturation

## Artifacts

`reliability/reports/<UTC>/sim-S1-<mode>/` → `pre.json`, `post.json`, `timeseries.jsonl`,
JTL/HTML, `verdict.md`.

## Cousins / out of scope

- Phase-1 scenarios 07/08/11 exercise full query or e2e — keep them; S1 is ACK-only.
- S2+ (kafka-inject, engine ITs, dispatcher, e2e, publish) are **follow-on PRs**.
