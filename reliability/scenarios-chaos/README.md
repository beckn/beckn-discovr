# Discovr — Phase 2 Chaos Scenarios

These scenarios deliberately destroy or impair cluster components to verify recovery, retry, idempotency, and graceful-degradation properties of the Discovr stack.

**Run only with the `discovr-reliability-chaos` agent.** The Phase 1 (`discovr-reliability`) agent is read-only and will refuse to execute these.

## Rules of engagement

- **Per-action user confirmation.** Each chaos action requires an explicit "proceed" from the user, with the precomputed abort/recovery command and current steady-state metrics printed first.
- **Steady-state gate before and after.** Each scenario starts and ends with a steady-state snapshot. Recovery must return to baseline within the budget.
- **Background load required.** Every chaos scenario runs JMeter as background load — injection happens mid-load.
- **One target per scenario.**
- **Cleanup is part of the scenario.** chaos-mesh CRDs must be deleted on completion (even on failure).

## Scenario index

| ID | Name | Target | Duration |
|----|------|--------|----------|
| 01 | Discovr publish-job pod kill mid-push | `catalog-publish-job` pod (Discovr side) | 25 min |
| 02 | Discover-job pod kill mid-query | `catalog-discover-job` pod | 25 min |
| 03 | Response-dispatcher pod kill mid-callback | `response-dispatcher` pod | 25 min |
| 04 | Elasticsearch node loss (1 of N) | one ES data node | 35 min |
| 05 | Elasticsearch cluster red — all shards on one node lost | ES shard primary blackhole | 30 min |
| 06 | Postgres primary failover | Postgres primary | 30 min |
| 07 | Kafka broker loss | one Kafka broker | 35 min |
| 08 | External enrichment API blackhole | external API URL pointed at no-response mock | 25 min |
| 09 | Rolling restart of each job under load | per-deployment rolling | 40 min |
| 10 | Combined: ES node loss DURING discover ramp | ES + load | 30 min |

## What "PASS" means in chaos

1. **No message loss** — Kafka offset committed == messages processed; no permanent data loss in DB, ES, or downstream callbacks.
2. **No duplicate effects** — no duplicate ES documents, no duplicate on_discover callbacks at the BAP.
3. **Bounded recovery** — latency, lag, ES cluster health return to steady-state within the scenario's recovery budget.
4. **No silent failure** — every error is in logs or DLT, never swallowed.
5. **Cleanup successful** — cluster state at the end == cluster state at the start, modulo committed catalog data.
