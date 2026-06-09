# Discovr Reliability Report — Exhaustive Template

This is the canonical structure of every `reports/<UTC-timestamp>/index.html` produced by the Discovr reliability agents (Phase 1 and Phase 2). The agent must render every numbered section below in order. Sections with no data this run still appear, marked `NOT_RUN` / `NO_DATA`.

The accompanying `scorecard.md` is just Section 1 in plain text — for paste-into-Slack / status doc use.

---

## 1. Executive Scorecard (HEADLINE)

| Area | Validation | Status | Score | Evidence |
|------|------------|--------|-------|----------|
| Availability | 99.9 % uptime target over the run window | PASS / FAIL / PARTIAL / NOT_RUN | actual uptime % | scenarios cited |
| Indexing Reliability | Every `catalog/push` reaches PG + ES; no loss, no duplicates | PASS / FAIL / PARTIAL | success rate % | scenarios cited |
| Search Reliability | Discover ACK + on_discover callback within SLO under load | PASS / FAIL / PARTIAL | success rate % | scenarios cited |
| Catalg → Discovr Sync | Every Catalg-ACKed catalog reaches Discovr; eventual consistency | PASS / FAIL / PARTIAL | sync success rate % | scenarios cited |
| Scaling | Sustained throughput at declared scale (1M items target) | PASS / FAIL / NOT_RUN | max sustained RPS / items indexed | scenarios cited |
| Failure Recovery | Auto-recovery from pod / ES node / Kafka / PG / external-API failures within budget | PASS / FAIL / NOT_RUN | recovery success rate | chaos scenarios cited |
| Backup | Schedule, retention, location, last verified (ES + PG + Velero) | DECLARED / STALE | freshness days | yaml keys |
| Restore | Last drill result, RTO observed vs target | DECLARED / STALE | RTO observed | yaml keys |
| Upgrade Reliability | No-downtime upgrade history per component (incl. ES major upgrade) | DECLARED / STALE | observed downtime | yaml keys |
| Monitoring | Metric coverage in ClickStack (ES + JVM + Kafka + external API) | PASS / PARTIAL | coverage % | metric list |
| Alerting | Required alerts configured + fired at least once in window | PASS / PARTIAL | configured / total | alert list |
| Known Gaps | Auto-derived + operationally declared | DERIVED | count | list |
| DR Strategy | "Rebuild Discover index from Catalog push events" — declared RPO/RTO, last drill | DECLARED / STALE | RTO target | yaml keys |

**Color rules:** green PASS, red FAIL, amber PARTIAL/STALE, grey NOT_RUN, blue DECLARED.

**Freshness check:** every DECLARED entry with `last_*_at` and `validity_days` is marked STALE if `(today - date) > validity_days`. STALE rows add an auto-derived Known Gap.

---

## 2. Run Metadata

| Field | Value |
|-------|-------|
| Agent | `discovr-reliability` (Phase 1) or `discovr-reliability-chaos` (Phase 2) |
| Agent version | (git short SHA of `.claude/agents/<file>.md`) |
| Cluster context | from `cluster.yaml` |
| GCP project | from `cluster.yaml` |
| GCP region | from `cluster.yaml` |
| JMeter VM | from `cluster.yaml` |
| ES cluster | name + node count + version |
| Run started | ISO-8601 UTC |
| Run ended | ISO-8601 UTC |
| Total scenarios requested | N |
| Scenarios PASS / FAIL / SKIPPED / UNKNOWN / ABORTED | counts |

---

## 3. Service Availability

### 3.1 Uptime breakdown
| Endpoint | Window | Samples | 2xx | 4xx | 5xx | Uptime % | Target | Status |
|----------|--------|---------|-----|-----|-----|----------|--------|--------|
| `/discover` (ACK) | run window | from JMeter JTL | | | | % | 99.9 | |
| `on_discover` callback delivery | run window | counted at JMeter listener | | | | % | 99.5 | |
| `/catalog/push` receive | run window | | | | | % | 99.9 | |

### 3.2 Error class breakdown (5xx)
| Error code | Count | Sample stack / log (sanitized) |

### 3.3 Pod restarts during run
| Deployment | Restart count | Notes |
| `catalog-publish-job`, `catalog-discover-job`, `response-dispatcher` | … | "1 restart at 14:23 — Phase 2 D03 injection (expected)" |
| ES data nodes | … | (Phase 2 D04/D05/D10 will show here) |

### 3.4 ES cluster status across run
| Sample (UTC) | Cluster status | Unassigned shards | Initializing shards | Active shards % |

### 3.5 Availability source scenarios
List of scenario ids that fed this section.

---

## 4. Indexing Reliability (push → PG + ES)

### 4.1 Per-scenario throughput + latency
| Scenario | Mode | Concurrency | Payload size | Duration | Sustained RPS | p50 push receive | p95 | p99 | ES bulk p99 | Status |
|----------|------|-------------|--------------|----------|---------------|------------------|-----|-----|-------------|--------|

### 4.2 PG ↔ ES consistency check
| Scenario | Items in PG | Docs in ES | Match? | Duplicates in ES | Notes |
| 01 | … | … | YES/NO | 0 | |

### 4.3 Idempotency checks (duplicate pushes)
| Scenario | Strategy | Sent N times | Final state in PG | Final state in ES | Status |
| 04 | Same catalogId × N | 5 | last-writer-wins | last-writer-wins | PASS/FAIL |

### 4.4 Retry behavior
- Discovr publish-job retries on transient ES failure (when applicable): PASS/FAIL
- Kafka offset only committed on full PG+ES success: PASS/FAIL (verified by Phase 2 D04/D05)

---

## 5. Search Reliability (discover + on_discover async)

### 5.1 Discover ACK throughput + latency
| Scenario | Concurrency | Duration | Sustained RPS | ACK p50 | ACK p95 | ACK p99 | ACK SLO | Status |
| 07 | … | … | … | … | … | … | 500 ms | … |
| 08 | ramp | … | … | … | … | … | 500 ms | … |
| 09 | mixed read/write | … | … | … | … | … | 500 ms | … |
| 10 | scale | … | … | … | … | … | 500 ms | … |

### 5.2 on_discover async callback E2E
| Scenario | Discovers sent | on_discover received | Lost | Duplicates | E2E p50 | E2E p95 | E2E p99 | SLO | Status |
| 11 | … | … | … | … | … | … | … | 3000 ms | … |
| 13 (soak) | … | … | … | … | … | … | … | 3000 ms | … |
| 14 (E2E) | … | … | … | … | … | … | … | 3000 ms | … |

### 5.3 Search under disruption
| Scenario | Disruption | Discover ACK rate dip | on_discover dip | Recovery time | Status |
| 12 (bad external) | slow / 503 / 500 | … | … | … | … |
| D04 (Phase 2) | ES node loss | … | … | … | … |
| D10 (Phase 2) | ES node loss DURING ramp | … | … | … | … |

### 5.4 ES query latency at scale
| Dataset size | ES query p50 | p95 | p99 | ES heap % | Status |
| 10k | … | … | … | … | … |
| 100k | … | … | … | … | … |
| 1M | … | … | … | … | … |

---

## 6. Catalg → Discovr Synchronization Reliability

End-to-end view from Catalg publish to Discovr ES indexed.

### 6.1 E2E sync verification
| Scenario | Catalogs sent (Catalg) | Reached Discovr ES | Lost | Duplicates | Median sync latency | p99 sync latency | Status |
| 14 (Discovr E2E) | … | … | … | … | … | … | … |

### 6.2 Sync under disruption
| Scenario | Disruption | Backlog at peak | Drain time | Loss | Duplicates | Status |
| D07 | Kafka broker loss | … | … | 0 | 0 | … |
| D12 | Cross-service partition | … | … | … | … | … |

### 6.3 Catalog deactivation reflection
- Phase 1 scope excluded FULL mode and explicit deactivation
- **DECLARED GAP**: see Known Gaps

---

## 7. Scalability

### 7.1 Items indexed scale
| Dataset | Discover RPS sustained | Discover p99 | ES heap % | Status | Source scenario |
| 10k items | … | … | … | … | 10 |
| 100k items | … | … | … | … | 10 |
| 1M items | … | … | … | … | 10 |

### 7.2 Push throughput scale
| Push concurrency | Sustained RPS | p99 push receive | ES bulk reject % | Status |
| 10 | … | … | … | … |
| 30 | … | … | … | … |
| 100 | … | … | … | … |

### 7.3 Discover throughput scale
| Discover concurrency | Sustained RPS | ACK p99 | on_discover p99 | Status |
| 30 | … | … | … | … |
| 100 | … | … | … | … |
| 200 | … | … | … | … |

### 7.4 Ceiling found
A single statement: "Sustained N discovers/sec against M items indexed, ACK p99 X ms, on_discover p99 Y ms — beyond this, SLO band exceeded."

---

## 8. Failure Recovery (Phase 2)

### 8.1 Per-failure-mode recovery table
| Failure mode | Scenario | Recovery budget | Recovery observed | Message loss | Duplicate effects | Manual intervention | Status |
| Discovr publish-job pod kill | D01 | 120 s | … | 0 | 0 | none | … |
| Discover-job pod kill | D02 | 120 s | … | … | … | … | … |
| Response-dispatcher pod kill | D03 | 120 s | … | … | … | … | … |
| ES node loss (1 of N) | D04 | 300 s to GREEN | … | … | … | … | … |
| ES cluster RED (multi-node) | D05 | 300 s | … | … | … | … | … |
| Postgres failover | D06 | 90 s | … | … | … | … | … |
| Kafka broker loss | D07 | 90 s consumer | … | … | … | … | … |
| External API blackhole | D08 | per-scenario | … | n/a | n/a | … | … |
| Rolling restart | D09 | per-deployment | … | … | … | … | … |
| ES node loss during ramp | D10 | 300 s | … | … | … | … | … |
| Node drain | D11 | 60 s pods + 300 s ES | … | … | … | … | … |
| Cross-service partition | D12 | 600 s drain | … | … | … | … | … |

### 8.2 Aggregate
- Total chaos actions injected: N
- Auto-recovery: M of N
- Manual intervention required: K of N (FAIL on the row if K > 0)
- Headline statement.

---

## 9. Backup & Restore

### 9.1 Backup status (from `operational-status.yaml`)
| Component | Enabled | Schedule | Retention | Location | Last verified | Validity | Freshness |
| Elasticsearch | bool | cron | days | snapshot repo | date | days | OK/STALE |
| Postgres | bool | cron | days | bucket | date | days | OK/STALE |
| Velero (k8s resources + PVs) | bool | cron | days | bucket | date | days | OK/STALE |

### 9.2 Restore drill history
| Component | Last drill | Result | RTO target | RTO observed | Validity | Freshness |
| Elasticsearch | date | … | min | min | days | OK/STALE |
| Postgres | … | … | … | … | … | … |
| Full environment | … | … | … | … | … | … |

### 9.3 Issues found in current run
- ES snapshot repo reachable from cluster (`_snapshot/_status` read-only): PASS/FAIL
- Snapshot GCS bucket exists, latest object < retention age: PASS/FAIL

---

## 10. Upgrade Reliability

| Component | Last upgrade | Result | Downtime observed | Method | Validity | Freshness |
| publish-job | date | PASS/FAIL/NOT_TESTED | seconds | rolling | days | OK/STALE |
| discover-job | … | … | … | rolling | … | … |
| response-dispatcher | … | … | … | rolling | … | … |
| Elasticsearch | … | … | … | rolling / blue-green | … | … |
| Postgres | … | … | … | … | … | … |

Live-tested in this run via D09 (rolling restart).

---

## 11. Monitoring & Alerting

### 11.1 Metric coverage check
| Metric | Source | Found in ClickStack? | Sample count | Status |
| `http.server.duration` | OTel (discover API) | YES/NO | N | PASS/FAIL |
| `kafka_consumer_records_lag_max` | Micrometer | … | … | … |
| `process.runtime.jvm.memory.usage` | OTel JVM | … | … | … |
| `process.runtime.jvm.gc.duration` | OTel JVM | … | … | … |
| `hikaricp.connections.usage` | Micrometer | … | … | … |
| `elasticsearch_indices_indexing_index_total` | ES exporter | … | … | … |
| `elasticsearch_indices_search_query_total` | ES exporter | … | … | … |
| `elasticsearch_cluster_health_status` | ES exporter | … | … | … |
| `elasticsearch_jvm_memory_used_bytes` | ES exporter | … | … | … |
| `elasticsearch_thread_pool_rejected_total` | ES exporter | … | … | … |
| `kubernetes.pod.cpu.usage` | OTel k8s | … | … | … |
| `kubernetes.pod.memory.usage` | OTel k8s | … | … | … |
| (custom from `operational-status.yaml`) | … | … | … | … |

### 11.2 Alert configuration check
| Alert | Configured? | Last fired | Freshness | Runbook | Status |
| `indexing_failures_high` | … | … | … | … | … |
| `discovr_sync_delay_high` | … | … | … | … | … |
| `elasticsearch_cluster_yellow_or_red` | … | … | … | … | … |
| `elasticsearch_jvm_heap_high` | … | … | … | … | … |
| `kafka_consumer_lag_high` | … | … | … | … | … |
| `postgres_connection_pool_exhausted` | … | … | … | … | … |
| `external_enrichment_api_failure_rate_high` | … | … | … | … | … |
| `on_discover_callback_5xx_rate` | … | … | … | … | … |

### 11.3 Runbook presence
Each alert with `runbook` field — checked exists. Missing runbooks → Known Gap.

---

## 12. Known Gaps & Operational Risks

Bulleted list. Each item:
- **Source** — `SCENARIO_SKIPPED` / `SCENARIO_UNKNOWN` / `OPERATIONAL_DECLARED` / `STALE_DECLARATION` / `MISSING_RUNBOOK`
- **Severity** — `HIGH` / `MEDIUM` / `LOW`
- **Identifier**
- **Note**

Example:
- `[HIGH] OPERATIONAL_DECLARED — known_gaps[0] — No automated catalog deletion, manual cleanup`
- `[MEDIUM] STALE_DECLARATION — restore.elasticsearch.last_drill_at — last drill 130d ago, validity 90d`
- `[LOW] SCENARIO_SKIPPED — D05 — RED cluster scenario not run this cycle`

---

## 13. DR Strategy

| Field | Value |
| Strategy | "Rebuild Discover index from Catalog catalog/push event stream + replay from Kafka if available" |
| RPO (minutes) | target |
| RTO (minutes) | target |
| Last drill | date |
| Last drill result | PASS/FAIL/NOT_TESTED |
| Runbook | link |
| Freshness | OK/STALE |

---

## Appendix A — Per-Scenario Detail

One subsection per scenario. Format same as Catalg template.

---

## Appendix B — Raw Measurements

One table per scenario. Long, scrollable.

---

## Appendix C — Action Log (Phase 2 only)

Chronological list of every chaos action. Includes ES-specific actions (cluster health transitions, shard reassignments observed).

---

## Appendix D — SLO Catalog Used

SLO thresholds the run evaluated against — from scenario frontmatter + `config/slos.yaml` fallbacks.

---

## Rendering rules

- Self-contained HTML, inline CSS
- Color rules: green PASS, red FAIL, amber PARTIAL/STALE/UNKNOWN, grey NOT_RUN, blue DECLARED
- Section anchors for scorecard "Evidence" links
- `NOT_RUN` rows never silently dropped

---

## Per-Scenario Report Template (`report.md`)

**Every scenario produces its own standalone Markdown report** at `reports/<ts>/<scenario>/report.md`. Plain Markdown — easy to diff, paste, convert. Must stand alone — a reader with only this file should understand what was tested and the result.

The consolidated `index.html` hyperlinks every summary-table row to that scenario's `report.md`.

### Template

```markdown
# <SCENARIO_ID> — <Scenario Name>

**Group:** <group>  **Phase:** <1 | 2>  **Duration:** <h:mm:ss>  **Result:** **<PASS | FAIL | PARTIAL | SKIPPED | ABORTED_BY_USER | SKIPPED_NOT_STEADY | INFRA_ERROR | ABORTED_INFRA | NOT_RUN>**

> One-sentence summary. If FAIL/PARTIAL, name the dominant cause.

---

## 1. Scenario Definition

- **Goal:** <copy from scenario file>
- **JMeter:** `<jmx-file>`, users=<N>, ramp=<s>, duration=<s>
- **Custom flags:** `-Jrps=20 -Jcatalog_size=medium ...`
- **External enrichment URL:** from `cluster.yaml`
- **Declared SLOs:** see §3

## 2. Run Metadata

| Field | Value |
|---|---|
| Cluster context | from `cluster.yaml` |
| GCP project | from `cluster.yaml` |
| JMeter VM | name + zone |
| Start UTC | ISO-8601 |
| End UTC | ISO-8601 |
| Duration | actual |
| Agent | `discovr-reliability` or `discovr-reliability-chaos` |
| Operator | gcloud account |

## 3. SLO Results

| SLO | Budget | Measured | Source | Status |
|---|---|---|---|---|
| Push receive p99 | < 500 ms | 380 ms | ClickStack `http.server.duration` /catalog/push | PASS |
| Indexer→ES write lag p95 | < 5 s | 2.1 s | ClickStack `elasticsearch_bulk_*` | PASS |
| Discover ACK p99 | < 300 ms | 220 ms | ClickStack `http.server.duration` /discover | PASS |
| on_discover E2E p99 | < 3 s | 2.4 s | ClickStack span discover → on_discover | PASS |
| ES query p99 | < 800 ms | 612 ms | ClickStack `elasticsearch_query_*` | PASS |
| ES cluster status end-of-run | GREEN | GREEN | `_cluster/health` | PASS |

## 4. Pre vs Post Snapshot

| Metric | Pre | Post | Δ |
|---|---|---|---|
| Kafka lag — publish-job | 0 | 8 | +8 |
| publish-job JVM heap % | 39 % | 58 % | +19 pp |
| discover-job JVM heap % | 32 % | 47 % | +15 pp |
| ES doc count | 102,400 | 122,400 | +20,000 |
| `catalog_index` rows | 10,240 | 12,240 | +2,000 |
| ES cluster status | GREEN | GREEN | unchanged |
| ES unassigned_shards | 0 | 0 | unchanged |

## 5. Time-Series Summary

| Metric | Min | Avg | p95 | p99 | Max |
|---|---|---|---|---|---|
| Push receive p99 (ms) | 240 | 312 | 360 | 380 | 392 |
| ES bulk rate (docs/s) | 850 | 1,200 | 1,720 | 1,820 | 1,840 |
| Discover ACK p99 (ms) | 140 | 190 | 210 | 220 | 230 |
| ES query p99 (ms) | 380 | 480 | 580 | 612 | 640 |

## 6. JMeter Summary

| Sampler | Count | rps | p50 | p90 | p95 | p99 | Error % |
|---|---|---|---|---|---|---|---|
| POST /catalog/push | 30,420 | 50.7 | 78 ms | 220 ms | 310 ms | 380 ms | 0.04 |
| POST /discover | 6,080 | 10.1 | 92 ms | 180 ms | 205 ms | 220 ms | 0.02 |
| on_discover received | 6,080 | 10.1 | 1.4 s | 2.0 s | 2.3 s | 2.4 s | 0.00 |

## 7. Pod Restarts & Errors

| Pod | Restarts | Last exit reason | ERROR count |
|---|---|---|---|
| discovr-catalog-publish-job-… | 0 | — | 0 |
| discovr-catalog-discover-job-… | 0 | — | 3 |
| discovr-response-dispatcher-… | 0 | — | 0 |
| elasticsearch-data-… | 0 | — | 0 |

## 8. ES Cluster Health Timeline  *(omit if ES status did not change)*

| UTC | Status | active_shards | unassigned | initializing | event |
|---|---|---|---|---|---|
| T+0   | GREEN  | 60 | 0  | 0 | steady |
| T+5m  | YELLOW | 50 | 10 | 0 | chaos: ES node killed |
| T+5m3s| YELLOW | 50 | 8  | 2 | shard recovery starting |
| T+7m48s| GREEN | 60 | 0  | 0 | recovery complete |

## 9. Chaos Action Log  *(Phase 2 only)*

| # of N | UTC | Target | Command | User input | Outcome | Recovery time | Budget |
|---|---|---|---|---|---|---|---|
| 1 of 1 | 2026-06-09T14:05:12Z | elasticsearch-data-2 | `kubectl delete pod -n elastic elasticsearch-data-2` | proceed | rescheduled, GREEN | 2m 36s | 5m |

## 10. Recovery Timeline  *(Phase 2 only)*

```
T+0      steady state — push p99 = 312 ms, ES = GREEN
T+5m12s  chaos injected — delete pod elasticsearch-data-2
T+5m13s  ES → YELLOW, 10 unassigned shards
T+5m15s  shard recovery initializing
T+6m20s  half shards recovered
T+7m48s  ES → GREEN, 0 unassigned
T+10m    recovery budget elapsed — SLO PASS (recovered at T+7m48s)
```

## 11. Raw Artifacts

- `pre.json` / `post.json` — metric snapshots
- `pre/es-state.json` / `post/es-state.json` — `_cluster/health` + `_cat/shards`
- `timeseries.jsonl`
- `jmeter/index.html`
- `cleanup.log` *(Phase 2 only)*
- `../index.html` — consolidated run report
- `../../scorecard.md` — overall scorecard
```

### Rules

- Result banner (top) is the first thing visible — bold and explicit.
- Times UTC, ISO-8601, second precision.
- Missing metric → `UNKNOWN — metric not exported`, never blank.
- Never inline raw JSON — link to it.
- On `INFRA_ERROR` / `ABORTED_*`: render §3–§7 with whatever was captured; banner + first paragraph explain the abort cause.
- Phase 1 reports omit §9 and §10 entirely.
- §8 (ES Cluster Health Timeline) is mandatory whenever ES status transitioned in the window — Phase 1 or Phase 2.
- The consolidated run `index.html` links to `report.md` — that link is the canonical "scenario detail" pointer.
