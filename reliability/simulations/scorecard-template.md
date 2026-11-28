# Simulation scorecard — corpus ladder

Fill after each rung. **S1 alone does not unlock a corpus column.**

Design: Agent Store `docs/corpus-ladder-simulation.md`  
SLOs: `../config/slos-100m.yaml`

## Gate matrix by corpus size

| Metric / sim | 1M | 10M | 50M | 100M |
|--------------|----|-----|-----|------|
| Corpus verified (ES≈PG≥99.9%) | | | | |
| S3 BM25 engine p99 (≤200 ms) | | | | |
| S3 PostGIS/J engine p99 (≤150 ms) | | | | |
| Sustained discover QPS | | | | |
| S5 e2e headline p99 (≤1.5 s) | | | | |
| S5 callback success (≥99.9%) | | | | |
| S6 push→searchable p99 (2–5 s) | | | | |
| S1 ACK p99 (optional isolation) | | | | |
| **Rung verdict** | | | | |

## Machine-readable stub

```yaml
gate: corpus_ladder
rungs:
  "1M":
    verified_count: null
    S1: SKIP
    S3: BLOCKED
    S5: BLOCKED
    S6: BLOCKED
    headline_bm25_p99_ms: null
    headline_postgis_p99_ms: null
    e2e_headline_p99_ms: null
    throughput_qps: null
  "10M":
    verified_count: null
    S3: BLOCKED
    S5: BLOCKED
    S6: BLOCKED
  "50M":
    verified_count: null
    S3: BLOCKED
    S5: BLOCKED
    S6: BLOCKED
  "100M":
    verified_count: null
    S3: BLOCKED
    S5: BLOCKED
decision: BLOCK
```

## Legacy S1-only row (ACK isolation)

| Sim | Profile | Registry mode | ACK p99 | Produce p99 | Error rate | Verdict | Report dir |
|-----|---------|---------------|---------|-------------|------------|---------|------------|
| S1 | | cache-hit | | | | | |
| S1 | | cache-miss | | | | | |

S2–S4: follow-on when kafka-inject / mock-onix land — still label every run with `corpus.profile`.
