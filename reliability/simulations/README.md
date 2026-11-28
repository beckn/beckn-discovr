# Discovr simulation harness (S1–S6) + corpus ladder

Path-split gates that prove ACK / engine / e2e / publish SLOs **against indexed
corpus sizes** before a Tier B prod SKU buy. Extends — does not replace — Phase-1
`reliability/scenarios/`.

| Sim | Status | Driver | Corpus required? |
|-----|--------|--------|------------------|
| **S1** ACK-only | Implemented | `jmx/s1-ack-only.jmx` | No (isolation; consumers paused) |
| S2 Kafka-in | Follow-on | `kafka-inject.sh` | Yes |
| **S3** Query headline | **Implemented (corpus ladder)** | `jmx/s3-query-headline.jmx` | **Yes** |
| S4 Dispatcher→onix | Follow-on | inject + mock-onix | No |
| **S5** Full e2e | **Implemented (corpus ladder)** | `jmx/s5-e2e-headline.jmx` | **Yes** |
| **S6** Publish→searchable | **Implemented (corpus ladder)** | `corpus-seed.sh` + `jmx/s6-publish-burst.jmx` | **Yes** |

**Corpus ladder (Praveen):** `1M → 10M → 50M → 100M` — see [`corpus-ladder.md`](./corpus-ladder.md).  
Design: Agent Store `docs/corpus-ladder-simulation.md` + `docs/simulation-harness-design.md`  
Architecture: Agent Store `docs/scalability-100m-architecture.md` §11  
SLOs: `../config/slos-100m.yaml`  
Profiles: `../config/corpus-profiles.yaml`

## Quick start — corpus-backed 1M

```bash
# Plan
./reliability/scripts/corpus-seed.sh --profile 1M --dry-run

# Load + verify (needs ES_URL + PG_URL on GCP/staging)
./reliability/scripts/corpus-seed.sh --profile 1M --mode bulk
./reliability/scripts/corpus-verify.sh --profile 1M

# Consumers ON — then query sims
./reliability/scripts/sim-run.sh S3 --profile 1M --path mix --qps 200 --dry-run
./reliability/scripts/sim-run.sh S5 --profile 1M --path T --headline-only --qps 200 --dry-run
./reliability/scripts/sim-run.sh S6 --profile 1M --mode sample --dry-run
```

Bulk seed attaches the sim index to `ES_ALIAS` (default `beckn-catalog`) and restores
`refresh_interval`. Verify counts **through the alias** and golden T/G search probes.
S3/S5 JMeter = ACK; engine/e2e p99 from ClickStack.

Agent VM without datastores: use `--profile local-smoke` and `--dry-run` extensively.

## S1 ACK-only (optional isolation)

```bash
./reliability/scripts/pause-discover-consumers.sh --apply-env
./reliability/scripts/sim-run.sh S1 --profile 1M --tpm 12000 --registry-mode cache-hit --dry-run
```

S1 PASS does **not** fill the corpus scorecard column — S3 or S5 must PASS against verified counts.
