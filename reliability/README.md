# Discovr Reliability Testing — Phase 1 (Load + Soak)

Reliability test suite for the Discovr stack (catalog-publish-job + catalog-discover-job + response-dispatcher) running on a dedicated GKE staging cluster.

**Phase 1 scope**: load and soak testing only. No chaos / fault injection.

## What this is

A scenario-driven reliability suite. JMeter (running on a dedicated VM) drives load against the staging cluster's public endpoints. The `discovr-reliability` agent reads scenario files, triggers JMeter runs via `gcloud compute ssh`, queries ClickStack (OpenTelemetry/ClickHouse) for the metrics each scenario cares about, and generates a consolidated HTML report.

## Scope (Phase 1)

Two flows are exercised:

1. **Indexing path** — Discovr is subscribed to a network on Catalg. Catalg distributes catalogs via `/catalog/push`. Discovr's `catalog-publish-job` consumes pushes and indexes into PostgreSQL/PostGIS + Elasticsearch. We stress: ingest throughput, ES + PG write lag, soak stability, large payloads, concurrent same-catalogId pushes.
2. **Query path** — BAPs send `POST /discover` to Discovr. Discovr ACKs synchronously, queries PG + ES asynchronously, enriches via an external API, and emits `on_discover` via the `response-dispatcher`. We stress: discover throughput, on_discover callback latency, mixed load (push + discover), query scaling as the index grows, behavior when the external enrichment API is slow or failing.

Out of scope for Phase 1: chaos / fault injection, subscription registration perf, pull API.

## Hard guardrails

The agent enforces these — it aborts the run if any are violated:

- **One kubectl context only** — read from `config/cluster.yaml`; the agent aborts if `kubectl config current-context` differs.
- **Read-only kubectl** — only `get`, `describe`, `logs`, `top`. No `apply`, `delete`, `patch`, `exec`, `scale`, `port-forward`.
- **Namespace allowlist** — even reads confined to namespaces listed in `config/cluster.yaml`.
- **One JMeter VM** — `gcloud compute ssh` invoked only with the VM name and zone from `config/cluster.yaml`.
- **ClickStack read-only** — query API only.
- **No DB writes, no Kafka admin, no ES admin** — read-only inspection only.
- **No chaos** — Phase 1 explicitly excludes pod-kill, broker-restart, network-partition, ES node fail.

## Layout

```
reliability/
  README.md
  config/
    cluster.yaml                   # allowed context, VM, endpoints, namespaces (placeholders)
    slos.yaml                      # default latency/error/lag/index budgets
  scenarios/
    01-..14-*.md                   # one file per scenario
  scripts/
    verify-cluster.sh
    jmeter-trigger.sh
    clickstack-query.sh
  reports/                         # generated HTML reports (gitignored)
```

## Prerequisites

1. Fill in `config/cluster.yaml` placeholders.
2. `gcloud auth login` + project set.
3. `kubectl config current-context` set to the allowed staging context.
4. JMeter scripts already deployed on the VM at the path listed in `cluster.yaml`.
5. **Catalg → Discovr subscription** already established (Discovr subscribed to a network on Catalg). The Catalg side feeds the index — Discovr's reliability cannot be tested without that flow live.
6. **External enrichment API** — a mockable / configurable endpoint that the `on_discover` flow calls. Some scenarios deliberately fault this endpoint.

## Running

```
@discovr-reliability run scenario 01
@discovr-reliability run all
@discovr-reliability run group indexing
@discovr-reliability run group query
@discovr-reliability run group soak
```

The agent writes one consolidated HTML report per run to `reports/<UTC-timestamp>/`.

## Scenarios (14)

| # | Name | Group |
|---|---|---|
| 01 | Baseline push receive + index | indexing |
| 02 | Push throughput ramp | indexing |
| 03 | Push sustained soak — 4 h | soak |
| 04 | Concurrent same-catalogId push | indexing |
| 05 | Large catalog push payload sweep | indexing |
| 06 | ES + PG write lag under push burst | indexing |
| 07 | Baseline discover query | query |
| 08 | Discover throughput ramp | query |
| 09 | Discover under concurrent push (mixed load) | query |
| 10 | Discover query at scale (10k → 1M indexed) | query |
| 11 | on_discover async callback success throughput | query |
| 12 | on_discover with slow / failing external API | query |
| 13 | on_discover async callback soak — 4 h | soak |
| 14 | E2E push → searchable + discover → on_discover p99 | query |

Detail in `scenarios/NN-*.md`.
