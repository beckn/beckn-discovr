# Discovr Reliability — Execution Runbook

How to invoke the reliability agents, scope a run by phase + scenario, and where the artifacts land.

For *what* each scenario tests, see `scenarios/NN-*.md` (Phase 1) and `scenarios-chaos/DNN-*.md` (Phase 2).
For *how* the report is rendered, see `REPORT_TEMPLATE.md`.

---

## TL;DR — one-page cheat sheet

| You want to… | Invoke | Notes |
|---|---|---|
| Sanity-check a single load case | `@discovr-reliability run scenario 01` | ~5–10 min |
| Run all indexing-path scenarios | `@discovr-reliability run group indexing` | ~30 min |
| Run all query-path scenarios | `@discovr-reliability run group query` | ~45 min |
| Overnight soak (4h scenarios) | `@discovr-reliability run group soak` | ~4–5 h |
| Full Phase 1 sweep | `@discovr-reliability run all` | ~5–6 h with soak |
| Preview a Phase 1 scenario (no JMeter) | `@discovr-reliability dry-run scenario 03` | reads ClickStack only |
| Inject a single chaos action | `@discovr-reliability-chaos run scenario D01` | each action waits for `proceed` |
| Preview a chaos scenario (no inject) | `@discovr-reliability-chaos dry-run scenario D04` | prints confirm block only |
| Re-render HTML from last run | `@discovr-reliability-chaos report only` | zero risk |

Phase 1 is read-only. Phase 2 mutates the cluster only with **per-action user confirmation**.

---

## 0. Prerequisites — fill these before invoking any agent

The agent aborts at guardrail check 1 if any `<FILL_*>` placeholder remains in a field it would read.

| File | Fields to fill |
|---|---|
| `config/cluster.yaml` | `cluster.context`, `cluster.project`, `namespaces.allowed[]`, `jmeter.vm_name`, `jmeter.zone`, `jmeter.user`, `jmeter.work_dir`, `clickstack.base_url`, `endpoints.push_url`, `endpoints.discover_url`, `external_enrichment.url`, `kafka.consumer_groups[]` |
| `config/slos.yaml` | only if you want non-default latency / error / lag / index budgets |
| `operational-status.yaml` | `backup.*` (ES, PG, Velero) with `last_verified_at` + `validity_days`; `restore.*` drill dates; `dr.*` runbook + RPO/RTO; `scaling.*` targets (1M items indexed, RPS); `availability.target_uptime_pct`; `upgrades.*` per component (incl. ES major-version); `alerts.required[].configured` + `last_fired_at` |

Local environment, separate from config:

1. `gcloud auth login`
2. `gcloud config set project <staging-project>` — must match `cluster.project` in `cluster.yaml`
3. `kubectl config use-context <staging-context>` — must match `cluster.context`
4. JMeter `.jmx` files already deployed at `jmeter.work_dir` on the VM
5. **Catalg → Discovr subscription** already live (Discovr subscribed to a network on Catalg). The Catalg side feeds the index; Discovr reliability can't be measured without that flow live.
6. **External enrichment API** reachable from Discovr at the URL in `cluster.yaml`. Some scenarios deliberately fault it.
7. For Phase 2 chaos-mesh scenarios: `kubectl get crd networkchaos.chaos-mesh.org` returns 0

---

## 1. Phase selection — which agent to call

The agent name picks the phase. There is no "both phases at once".

| Phase | Agent | Allowed operations | Mutations |
|---|---|---|---|
| **Phase 1** — load + soak | `@discovr-reliability` | `kubectl get/describe/logs/top`, ClickStack reads, JMeter via ssh, read-only `SELECT`, read-only ES `_cat/_search` | **None** |
| **Phase 2** — chaos | `@discovr-reliability-chaos` | All of Phase 1, plus: `kubectl delete pod`, `kubectl rollout restart`, `kubectl rollout undo`, `kubectl apply -f` (chaos-mesh CRDs only) | Only as authorized by the scenario AND confirmed per-action |

If you call the wrong agent for a scenario (e.g. `@discovr-reliability run scenario D01`), it will refuse — Phase 1 doesn't load `scenarios-chaos/` files.

Note on Elasticsearch: Phase 2 will never run `_bulk` writes, `_reindex`, `_cluster` mutations, `_snapshot` actions, or index create/delete. The ES-targeted chaos (D04, D05) operates on the ES **pods**, not the ES API.

---

## 2. Phase 1 — load + soak

### 2.1 Invocations

```text
# Single scenario by number
@discovr-reliability run scenario 01
@discovr-reliability run scenario 14

# By group (matches `group:` in scenario frontmatter)
@discovr-reliability run group indexing    # 01, 02, 04, 05, 06
@discovr-reliability run group query       # 07, 08, 09, 10, 11, 12, 14
@discovr-reliability run group soak        # 03, 13 — 4h each

# Everything
@discovr-reliability run all

# Dry-run — guardrails + pre-snapshot + JMeter command print, no JMeter trigger
@discovr-reliability dry-run scenario 03
```

### 2.2 Scenario index (Phase 1)

| # | Name | Group | Approx duration |
|---|---|---|---|
| 01 | Baseline push receive + index | indexing | 5 min |
| 02 | Push throughput ramp | indexing | 15 min |
| 03 | Push sustained soak — 4 h | soak | 4 h |
| 04 | Concurrent same-catalogId push | indexing | 10 min |
| 05 | Large catalog push payload sweep | indexing | 20 min |
| 06 | ES + PG write lag under push burst | indexing | 20 min |
| 07 | Baseline discover query | query | 5 min |
| 08 | Discover throughput ramp | query | 15 min |
| 09 | Discover under concurrent push (mixed load) | query | 25 min |
| 10 | Discover query at scale (10k → 1M indexed) | query | 1 h |
| 11 | on_discover async callback success throughput | query | 20 min |
| 12 | on_discover with slow / failing external API | query | 20 min |
| 13 | on_discover async callback soak — 4 h | soak | 4 h |
| 14 | E2E push → searchable + discover → on_discover p99 | query | 30 min |

### 2.3 What Phase 1 does per scenario

1. Runs all guardrail checks (kubectl context, gcloud project, ClickStack reachable, namespace allowlist, ES cluster reachable).
2. Captures pre-run metric snapshot → `pre.json` (Kafka lag, JVM heap, HikariCP usage, ES cluster status, ES doc count, PG row counts, pod CPU/mem).
3. SSH'es to the JMeter VM and triggers the scenario's `.jmx` with parameters.
4. Polls ClickStack every 30 s while JMeter runs → `timeseries.jsonl`.
5. Waits for JMeter to exit, scp's the JTL + HTML report back.
6. Captures post-run snapshot → `post.json`.
7. Asserts every SLO listed in the scenario, records measured value next to each.
8. Writes `verdict.md` with PASS/FAIL per SLO.

After all requested scenarios: writes consolidated `index.html` + `scorecard.md`.

---

## 3. Phase 2 — chaos

### 3.1 Invocations

```text
# Single chaos scenario — strongly preferred
@discovr-reliability-chaos run scenario D01
@discovr-reliability-chaos run scenario D04

# Preview — prints the per-action confirmation block, never injects
@discovr-reliability-chaos dry-run scenario D05

# Everything (rare — only when re-baselining the whole suite)
@discovr-reliability-chaos run all chaos

# Rebuild the HTML/scorecard from the most recent run, no execution
@discovr-reliability-chaos report only
```

### 3.2 Scenario index (Phase 2)

| # | Name | Target | Approx duration |
|---|---|---|---|
| D01 | Discovr publish-job pod kill | catalog-publish-job | 10 min |
| D02 | Discover-job pod kill | catalog-discover-job | 10 min |
| D03 | Response-dispatcher pod kill | response-dispatcher | 10 min |
| D04 | ES single-node loss | one ES data pod | 15 min |
| D05 | ES cluster RED (two-pod kill) **destructive** | two ES data pods | 25 min |
| D06 | Postgres failover | CNPG/Patroni cluster | 20 min |
| D07 | Kafka broker loss | one broker pod | 20 min |
| D08 | External enrichment API blackhole | Option A: JMeter toggle. Option B: NetworkChaos | 15 min |
| D09 | Rolling restart of all jobs | publish → discover → response-dispatcher | 25 min |
| D10 | Combined ES loss during ramp | one ES pod under load | 20 min |
| D11 | Node drain — ES anti-affinity check | one worker node | 30 min |
| D12 | Cross-service network partition (Catalg ↔ Discovr) | NetworkChaos between delivery-job and publish-job | 20 min |

**D05 carries a special DESTRUCTIVE banner** — the agent prints an extra warning before action 1, because two ES pod kills will push the cluster to RED and unsearchable. Confirm only on a dedicated staging cluster.

### 3.3 The per-action confirmation protocol

For **every** chaos action in a scenario, the agent stops and prints exactly:

```text
─── CHAOS ACTION ─── Scenario DNN, action <K of M>

Target:       <pod-name> on <node>, uptime <Xm>
Steady state: <metric1=value1, metric2=value2, ...>
Command:      <exact command to be run>
Abort path:   <exact command to undo / recover>
Expected:     <what should happen — recovery budget>

Type "proceed" to inject, or "skip" to mark SKIPPED, or anything else to abort the scenario.
```

You type one of:

| Input | Effect |
|---|---|
| `proceed` | Runs the command, starts the recovery-budget timer, samples until steady state returns or budget elapses |
| `skip` | Marks that action SKIPPED, continues to next action |
| anything else | Aborts the scenario, runs cleanup if anything was injected |

The agent **never** infers; it only acts on those three responses.

### 3.4 What Phase 2 does per scenario

1. All Phase 1 guardrail checks, plus chaos-mesh CRD presence (if scenario uses it).
2. Triggers background JMeter load, waits for steady state (~5 min).
3. Pre-chaos snapshot → `pre.json` (incl. ES cluster status, doc count, replica state).
4. For each chaos action in the scenario:
   - Print confirmation block, wait for input.
   - On `proceed`: execute, start recovery timer, sample until budget elapses or steady state returns.
   - Run cleanup (delete chaos-mesh CRD if applied).
5. Post-recovery snapshot → `post.json`.
6. Waits for JMeter completion, fetches JTL + HTML.
7. Asserts SLOs, writes `verdict.md` with action log + recovery timings.

After all requested chaos scenarios: writes consolidated `index.html` + `scorecard.md` + `action-log.md`.

---

## 4. What gets written — directory layout

Every run creates a fresh UTC-stamped directory; re-runs never overwrite.

```
reliability/reports/<UTC-timestamp>/
├── index.html              # consolidated HTML report — open in browser
├── scorecard.md            # plain-text scorecard for Slack/doc paste
├── action-log.md           # every chaos action with timestamp + user "proceed" (Phase 2 only)
├── <scenario-id>/
│   ├── report.md           # ← STANDALONE per-scenario report (primary artifact)
│   ├── pre.json            # pre-run metric snapshot
│   ├── post.json           # post-run metric snapshot
│   ├── pre/es-state.json   # ES _cluster/health + _cat/shards (when scenario touches ES)
│   ├── post/es-state.json  # same, post-run
│   ├── timeseries.jsonl    # 30s-cadence ClickStack samples during the run
│   ├── jmeter/             # JTL + JMeter HTML report scp'd from the VM
│   └── cleanup.log         # chaos-mesh cleanup (Phase 2 only)
```

`report.md` is the canonical per-scenario report — plain Markdown, follows the "Per-Scenario Report Template" in `REPORT_TEMPLATE.md`. Result banner at the top, then sections for scenario definition, run metadata, SLO results, pre/post diff, time-series summary, JMeter summary, pod restarts, plus an ES Cluster Health Timeline whenever ES status transitioned, and a chaos action log + recovery timeline for Phase 2.

The consolidated `index.html` is the run-level view; every row hyperlinks to that scenario's `report.md`. No data is duplicated — `index.html` aggregates and links.

---

## 5. Recommended sequence to document a release

The Phase 1 scorecard auto-imports the most recent Phase 2 `scorecard.md` for the Recovery row. So run in this order:

```text
# 1. Baseline Phase 1 (load + soak)
@discovr-reliability run all          # ~5–6 h with soak; ~1 h without

# 2. Phase 2 chaos, one scenario at a time, with eyes on the screen
@discovr-reliability-chaos run scenario D01
@discovr-reliability-chaos run scenario D02
... through D12

# 3. Final Phase 1 re-run — picks up Phase 2 evidence for the Recovery row
@discovr-reliability run all

# The HTML from step 3 is what you ship.
```

If you skip step 2, the step-3 scorecard's **Recovery** row reads `NOT_RUN` with evidence "no Phase 2 chaos run on record". This is by design — you cannot accidentally claim recovery is fine without evidence.

### Cross-repo: D12 needs Catalg

D12 (cross-service network partition) requires Catalg's delivery-job to be reachable from the same cluster context. If Catalg is in a different cluster, D12 is `NOT_RUN`. Coordinate the partition test with whoever runs Catalg's chaos agent.

---

## 6. Troubleshooting — what each guardrail abort means

The agent writes `verdict.md` with the abort reason even if it never starts a scenario.

| Abort message | Cause | Fix |
|---|---|---|
| `Config has <FILL_*> placeholders` | `cluster.yaml` or `operational-status.yaml` has unfilled fields | Fill the listed fields |
| `kubectl context mismatch` | `kubectl config current-context` ≠ `cluster.context` | `kubectl config use-context <correct>` |
| `gcloud project mismatch` | `gcloud config get-value project` ≠ `cluster.project` | `gcloud config set project <correct>` — **the agent will not run this for you** |
| `ClickStack unreachable` | Network / auth issue to ClickStack base URL | Check VPN / token; the agent never retries silently |
| `Namespace not in allowlist` | Scenario references a namespace not in `namespaces.allowed` | Either add the namespace to the config or skip the scenario — **the agent will ask first** |
| `External enrichment unreachable` | The mock/real enrichment endpoint is down | Bring it up or skip scenarios 11/12/13/14 |
| `Catalg→Discovr subscription not active` | No catalogs flowing into the index | Re-establish the subscription before running indexing scenarios |
| `chaos-mesh CRDs missing` (Phase 2) | NetworkChaos / IOChaos CRDs not installed | Install chaos-mesh, or skip D08 / D12 |
| `Scope-widening request detected` | A scenario or your prompt asked the agent to do something forbidden | The agent refuses and asks. **Never override.** |

---

## 7. Boundaries — what the agents will refuse

These are hard-coded refusals; no flag toggles them on. If you genuinely need them, do them manually outside the agent.

- Any `gcloud` subcommand other than `compute ssh` / `compute scp` against the pinned JMeter VM
- Any cross-project access, even read-only
- Any service-account creation, key issuance, role grant, or impersonation
- Any cluster other than `cluster.context`
- Phase 1: any `kubectl` mutation
- Phase 2: any `kubectl` mutation outside `delete pod`, `rollout restart`, `rollout undo`, `apply -f` for chaos-mesh CRDs
- Any DB write, Kafka admin write
- Any ES mutation — no `_bulk` writes, no `_reindex`, no `_cluster` mutations, no `_snapshot` actions, no index create/delete (ES-targeted chaos operates on **pods**, not the ES API)
- Any chaos tool outside chaos-mesh CRDs declared in scenario files
- Any HTTP call to a host not listed in `cluster.yaml`

If a credential in your shell grants broader access, the agents still refuse to use it. That's intentional — narrow scope by design beats trusting ambient permissions.
