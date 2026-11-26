# Simulation scorecard (Tier gate stub)

Fill after each sim run. Machine-readable companion: emit
`reliability/reports/<UTC>/sim-scorecard.yaml` with the same rows.

Parent design: Agent Store `docs/simulation-harness-design.md` §9.
Architecture: Agent Store `docs/scalability-100m-architecture.md` §11.

```yaml
gate: tier_b
corpus: "<1M|15M|100M-subset>"
required:
  S1: "<PASS|FAIL|BLOCKED>"
  S2: BLOCKED          # not implemented — follow-on PR
  S3: BLOCKED
  S4: BLOCKED
  S5: BLOCKED
  S6: BLOCKED
  scenario_14: BLOCKED # restore/package with S5 gate
decision: BLOCK        # APPROVE_TIER_B only when all required = PASS
```

## S1 row (this PR)

| Field | Value |
|-------|-------|
| Sim | S1 ACK-only |
| Corpus profile | |
| Registry mode | cache-hit / cache-miss |
| QPS steps | 200 → 600 (→ optional 1k) |
| Consumers paused | yes / no |
| ACK p99 (hit) ms | ≤ 100? |
| ACK p99 (miss) ms | ≤ 250? |
| ACK error rate | ≤ 0.1%? |
| Kafka produce p99 ms | ≤ 20? |
| Artifact dir | `reliability/reports/.../sim-S1-...` |
| Verdict | PASS / FAIL |

## Tier B unlock

Blocked until **S1–S5 green at 15M** + **scenario 14** + **S3 cold/`nextCursor` drills** + **S6 green**.
S1 alone never unlocks Tier B spend.
