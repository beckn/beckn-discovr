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
query → `on_discover` path. Measures Tomcat/thread pool, schema validation, Kafka
request-topic produce, and (when signed) Beckn auth + registry cache hit/miss.

## Entry / exit

| | |
|--|--|
| **Entry** | `POST /beckn/discover` |
| **Exit** | Synchronous ACK/NACK HTTP response |
| **Not measured** | Engine query, dispatcher, callback delivery |

## Auth modes (important)

BecknAuth verifies `created`/`expires` (clock skew) and a **BLAKE2b digest of the
request body**. The JMX mutates `messageId` / `transactionId` / `timestamp` on every
sample, so a **static Authorization header always NACKs**.

| Mode | How | Gate use |
|------|-----|----------|
| **`auth-off` (default)** | `SIGNATURE_AUTH_ENABLED=false`; no `Authorization` header | S1 v1 ACK/Kafka evidence |
| **`cache-hit` / `cache-miss` / `registry-down`** | `--sign-url` signer digests **this** sample body; in-cluster registry mock | Auth/registry SLO rows |

`cache-miss` rotates `uniqueKeyId` per sample so the discover auth cache cannot
absorb registry RTT (TTL alone ≈ 0.5% misses at 200 QPS with one key).

## Infra

- `catalog-discover-job` + Kafka
- **Consumers paused** (`DISCOVERY_KAFKA_LISTENER_ENABLED=false` / profile `sim-s1`)
- On **resume**, offsets reset to **latest** for consumer group **`discovery-service`**
  (`spring.kafka.consumer.group-id`, not the Deployment name) before enabling the
  listener — avoids replaying ~targetRps×duration messages into query/callbacks.
  Or use a sim-only `KAFKA_REQUEST_TOPIC`.
- Registry mock: **in-cluster** Service URL in `simulation.registry_mock_url`
  (not `127.0.0.1` from the laptop)

## Load shape

Open-loop via JMeter `ConstantThroughputTimer` (`-JtargetRps`).

| Step | Target RPS | Sustain |
|------|------------|---------|
| 1 | 200 (~12k TPM) | 10–20 min |
| 2 | 600 (×3 peak) | 10–20 min |
| 3 (optional) | 1k–2k | 5–10 min |

Record **achieved RPS** from the JTL summary; gate evidence requires ≈ target (±10%).

## How to run

```bash
# Default — auth-off (measurable without signer)
./reliability/scripts/sim-run.sh S1 --profile 15M --tpm 12000 --dry-run
./reliability/scripts/pause-discover-consumers.sh --apply-env
./reliability/scripts/sim-run.sh S1 --profile 15M --tpm 12000

# Registry drills (require signer + in-cluster mock)
./reliability/scripts/mock-registry.sh write-mappings --mode cache-hit
# deploy mappings to cluster WireMock; set SIGNATURE_* on discover
./reliability/scripts/sim-run.sh S1 --registry-mode cache-hit \
  --sign-url https://signer.example/sign-payload --tpm 12000
./reliability/scripts/sim-run.sh S1 --registry-mode cache-miss \
  --sign-url https://signer.example/sign-payload --tpm 12000

./reliability/scripts/pause-discover-consumers.sh --resume-env
```

Direct JMeter props (after sync):

```text
jmeter-trigger.sh S1 s1-ack-only.jmx \
  -Jusers=<headroom> -Jduration=<sec> -JtargetRps=200 \
  -JdiscoverUrl=<endpoints.discovr_discover> \
  -JintentPath=intent-t.json \
  -JauthMode=auth-off -JregistryMode=auth-off
```

Property name is **`intentPath`** (not `intentFile`).

## SLOs (`slos-100m.yaml`)

| Metric | Pass |
|--------|------|
| ACK p99 auth-off | ≤ **100 ms** |
| ACK p99 cache-hit | ≤ **100 ms** |
| ACK p99 cache-miss | ≤ **250 ms** |
| ACK error rate | ≤ **0.1%** (excl. registry-down) |
| Kafka produce p99 | ≤ **20 ms** |
| Achieved RPS | ≈ target (±10%) |

## Fail fast

- ACK p99 over budget for the active mode
- Error rate > 0.1% (healthy modes)
- Kafka produce errors / thread pool exhaustion
- Achieved RPS far below target (timer/misconfig)
- Signed mode without working `--sign-url` (every sample NACK)
