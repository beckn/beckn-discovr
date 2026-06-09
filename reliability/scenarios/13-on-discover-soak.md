---
id: "13"
name: "Discover endpoint soak — discover-job + response-dispatcher under sustained query load"
group: soak
duration_min: 60
external_load: false
---

## Goal
Detect slow degradation on the discover path under sustained `/beckn/discover` query load:
- catalog-discover-job heap drift, GC drift, thread leak, connection-pool leak
- ES query-cache pressure, segment search load, JVM heap
- PG `jsonb_path_query` plan stability, lock contention, replication lag
- response-dispatcher HTTP callback delivery rate, retry / DLT accumulation
- Log error rate slope (signature failures, schema validation errors, query engine errors)

Distinct from scenario 15 (publish-receive monitor): here the discovr agent **drives load** via `discover-async-soak.jmx` against the cluster's own `/beckn/discover`. No external trigger.

## Pre-condition
- Index `beckn-catalog-groceryresource` is populated (≥ 1000 docs)
- BAP signing identity (`-Jbap.subscriber.id`, `-Jbap.record.id`, `-Jbap.private.key`) is registered in DeDi
- Bouncy Castle JAR is on the JMeter VM's classpath

## What is in scope
- `discovery/catalog-discover-job` — HTTP entry + Kafka producer to `discovr.discover.in.requests`
- `discovery/response-dispatcher` — Kafka consumer + HTTP callback sender
- `discovr-es/discovery-elasticsearch-master` — text search BM25 + JSONPath spatial queries
- `discovr-psql/discovery-infra-postgresql-primary` + `-read` — jsonb_path_query execution + replication
- `discovr-kafka/discovery-infra-kafka` — broker health + lag on `discovr.discover.*` topics

## What is explicitly out of scope (must stay flat — alert if it spikes)
- `discovery/catalog-publish-job` — no `/catalog/push` traffic expected during a discover-only soak
- If publish-job CPU > 5 % or its consumer.received rate > 0, log a WARNING — soak is polluted

## Load shape
- JMeter script: `discover-async-soak.jmx` (POST, async ACK path) — agent runs it via `jmeter-trigger.sh`
- Concurrent users: 30 (override with `-Jusers=N`)
- Duration: matches scenario `duration_min` (60 by default; agent honours invocation override)
- Ramp: 60 s
- Fixture: `../fixtures/text-queries-matched.csv` (2K vocab-derived queries that match published data)
- BAP signing identity passed as `-Jbap.subscriber.id / -Jbap.record.id / -Jbap.private.key`

## Metrics to capture (every 60 s — agent polls ClickStack)

### catalog-discover-job
- HTTP request rate (req/s) at `/beckn/discover`
- HTTP p50/p95/p99 latency
- HTTP 4xx + 5xx rate
- JVM heap used % — slope per hour
- GC pause p99 (Young + Old separately)
- Thread count — slope per hour
- ES query duration p99 (logged as `event=es.query.duration`)
- PG jsonpath query duration p99 (logged as `event=pg.jsonpath.duration`)
- Caffeine schema-validator cache hit ratio

### response-dispatcher
- Kafka consumer lag on `discovr.discover.out.responses`
- HTTP callback delivery p99 (BAP `/on_discover` POST)
- HTTP connection pool active + idle
- Retry count (per minute)
- DLT message count on `discovr.discover.dlt.responses` (must remain 0)
- JVM heap % — slope per hour

### Elasticsearch (discovery-elasticsearch-master)
- JVM heap used % — slope per hour
- GC pause p99
- Search rate (queries/sec)
- Query duration p99 + p50
- Query cache hit ratio
- Request cache hit ratio
- Fielddata cache + filter cache evictions
- Pending tasks count

### Postgres
- Active session count (primary)
- Replication lag to read replica (seconds)
- Slow query count (mean_exec_time > 500ms)
- WAL bytes generated (write volume — should be near 0 for discover-only soak)

### Kafka brokers (discovery-infra-kafka)
- Broker CPU + memory
- ISR shrinks
- Under-replicated partition count

### Cross-service
- Pod restarts in all in-scope namespaces (must remain 0)
- `k8s.pod.network.io` on discover-job + response-dispatcher

## Service log peeking (every 60 s)

### catalog-discover-job
Agent calls `kubectl -n discovery logs deploy/discovery-catalog-discover-job --since=60s --tail=500` and tallies:

- Count of `level=ERROR` lines (fail if > 0)
- Count of `event=discover.request.received` (rate proxy)
- Count of `event=discover.ack.success`
- Count of `event=es.query.completed`
- Count of `event=pg.jsonpath.completed`
- Count of `event=discover.auth.failed` — must remain 0
- Count of `event=discover.validation.failed` — must remain 0
- Last 3 ERROR / WARN sample lines embedded verbatim per hour
- Known-bad substrings: `OutOfMemoryError`, `Connection refused`, `Too many connections`, `SignatureVerifierException`, `JsonPathExecutionException`, `ElasticsearchException`, `consumer.rebalance`

### response-dispatcher
Agent calls `kubectl -n discovery logs deploy/discovery-response-dispatcher --since=60s --tail=500`:

- Count of `level=ERROR` lines (fail if > 0)
- Count of `event=callback.delivered`
- Count of `event=callback.failed`
- Count of `event=callback.retried`
- Sample last 3 ERROR / WARN lines verbatim
- Known-bad: `OutOfMemoryError`, `ConnectionRefused`, `ReadTimeout`, callback URL host unreachable

## PG peeking (every 60 s — agent runs `kubectl exec psql`)

- `SELECT count(*) FROM item;` — should be stable (discover doesn't write); flat = healthy, growing = unexpected
- `SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND query LIKE '%jsonb_path_query%';` — concurrent JSONPath query count
- `SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND datname = current_database();` — total active sessions
- `SELECT now() - pg_last_xact_replay_timestamp() AS lag;` on read replica
- Every 10 min: `SELECT query, calls, total_exec_time, mean_exec_time FROM pg_stat_statements WHERE query LIKE '%jsonb_path_query%' ORDER BY mean_exec_time DESC LIMIT 5;` — slowest JSONPath patterns for forensic forwarding to the perf team

If a query fails because of auth, record "PG peek unavailable: <error>" and continue.

## ES peeking (every 60 s — agent runs `kubectl exec curl localhost:9200`)

- `GET /beckn-catalog-groceryresource/_count` — must remain stable (read-only soak)
- `GET /_cluster/health` — status (`green` expected; alert on `yellow`/`red`)
- `GET /_nodes/stats/jvm` — heap used %, GC count + collection time
- `GET /_nodes/stats/indices/search` — search count + total_time_in_millis (compute search rate + avg ms)
- `GET /_nodes/stats/indices/query_cache` — query cache hit/miss ratio, memory size
- `GET /_nodes/stats/indices/request_cache` — request cache hit/miss ratio
- `GET /_cluster/pending_tasks` — must remain empty
- Every 10 min: `GET /beckn-catalog-groceryresource/_search?size=0&q=*` with `explain=true` for cache-state snapshot

## SLOs

### Latency
- HTTP p99 at `/beckn/discover` ≤ 800 ms sustained
- HTTP p99 drift ≤ 10 % from hour 1 to hour N
- ES query p99 ≤ 500 ms sustained
- PG JSONPath query p99 ≤ 500 ms sustained
- Callback delivery (response-dispatcher → BAP) p99 ≤ 300 ms sustained

### Stability
- discover-job heap drift ≤ 5 % per hour after first 30 min warmup
- response-dispatcher heap drift ≤ 5 % per hour
- ES heap drift ≤ 5 % per hour
- Thread count growth ≤ 10 per hour on each Java service
- Zero pod restarts across all in-scope namespaces
- DLT message count on `discovr.discover.dlt.responses` remains 0

### Throughput
- HTTP error rate (5xx) ≤ 0.1 % sustained
- HTTP error rate (4xx — auth fail etc.) ≤ 0.5 % sustained
- ES + PG cache hit ratios increase or stabilise after first 10 min (decreasing trend = cache thrash, fail)

### Hygiene (out-of-scope assertions)
- publish-job CPU stays < 5 % of its limit (no traffic expected)
- PG `item` table row count stays flat (no writes expected)

### Slope SLOs need ≥ 60 min
At ≤ 60 min: slope-based assertions report `INSUFFICIENT DATA` rather than fail. Point-in-time SLOs evaluated fully.

## Run procedure
1. Agent runs guardrails (cluster context, gcloud auth, project pin, ClickStack reachable, namespaces allowlist).
2. Agent confirms pre-conditions: ES `_count > 1000` AND publish-job consumer lag = 0 (so the cluster is fully caught up before discover load fires).
3. Agent calls `reliability/scripts/jmeter-trigger.sh 13 discover-async-soak.jmx -Jusers=<users> -Jduration=<secs> -Jbap.subscriber.id=... -Jbap.record.id=... -Jbap.private.key=...`
4. Agent **starts the 60-second polling loop** for the full duration. Continues even if SLOs breach; only stops if an external abort comes (Ctrl+C / TaskStop) or guardrails detect cluster fall-over (pod restart).
5. Every hour: write a partial report at `reliability/reports/13-<UTC-ts>/report-hour-N.md` with current SLO state + per-hour slope.
6. After JMeter exits: agent copies the JTL back from the VM to `reliability/reports/13-<UTC-ts>/jmeter.jtl`, parses summary stats (samples, p50/p95/p99, err%, RPS), embeds in the final report.
7. Final report at run-end with the full SLO table + JMeter results + per-hour log/PG/ES peek samples + known-bad incidents + HyperDX dashboard link + comments.

## Report extras (must include for "all types of result")
- **JMeter results summary** — samples, RPS, p50/p95/p99 latency, error rate (from JTL)
- **Per-hour SLO pass/fail/insufficient-data table**
- **Slope curves as CSV** for: discover-job heap %, response-dispatcher heap %, ES heap %, thread counts, query p99, callback p99
- **PG slow query top-5** at each 10 min interval (last snapshot embedded in full)
- **ES cache stats curve** — query_cache + request_cache hit ratio per hour
- **ERROR log excerpts** — every ERROR line captured during the run, verbatim, with timestamp + service + thread + messageId
- **Out-of-scope alert log** — every minute where publish-job CPU > 5 % or `item` count changed, with the offending data
- **HyperDX dashboard link** with `from=<start>&to=<end>` URL params
- **Cross-reference** — if scenario 15 ran concurrently, link to its report dir
- **`comments:` field** — narrative summary of anything unusual the agent observed but didn't auto-fail

## Notes
- ES query-cache hit ratio CLIMBING across the soak is the healthy steady-state signal. If it DECLINES after hour 1, queries are too diverse for cache fit and you're hitting Lucene scans constantly — re-evaluate query selectivity.
- For a single-template publish soak (uniform catalog content), use a wide-match query instead of the 2K vocab fixtures: `"SoakBrand"` for text, `$..identity.brand == "SoakBrand"` for jsonpath. Pass via `-Jfixture.text=<file>` override.
- This scenario shares load with scenario 11 (on_discover async callback) and scenario 12 (on_discover bad external API). Don't run concurrently — they will interfere.
- If response-dispatcher's HTTP callback target is the mock callback-sink in the cluster, expect ~10 ms p99 latency. If it's an external real BAP, p99 can be much higher and that's not a regression.
