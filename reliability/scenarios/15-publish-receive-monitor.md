---
id: "15"
name: "Publish receive monitor — observe Discovr ingest during external publish load"
group: monitor
duration_min: 240
external_load: true
---

## Goal
Observe the Discovr ingest path while the **Catalg-side reliability agent** (different repo, different runner) drives a 4-hour publish soak against `/beckn/catalog/publish`. The Catalg fanout chain pushes catalogs to Discovr's `/catalog/push` endpoint, which lands in Kafka and is consumed by `catalog-publish-job`. This scenario watches that consumer, its Kafka backlog, and the downstream PG + ES stores.

This scenario is **observe-only**. The discovr agent does NOT trigger any JMeter run on its end. It assumes load is happening from outside.

## What is in scope
- `discovery/catalog-publish-job` — the consumer that receives fanned-out catalogs
- `discovr-kafka/discovery-infra-kafka` — broker health + consumer-group lag for `catalog-publish-group`
- `discovr-psql/discovery-infra-postgresql-primary` — item upserts
- `discovr-psql/discovery-infra-postgresql-read` — replication lag
- `discovr-es/discovery-elasticsearch-master` — indexing rate + heap

## What is explicitly out of scope (must stay flat — alert if it spikes)
- `discovery/catalog-discover-job` — only serves `/beckn/discover`; no traffic expected
- `discovery/response-dispatcher` — only fires after a discover request; no on_discover work expected

If either of the above shows non-trivial CPU / network during the soak, log a WARNING — something external is also hitting the discover path and the soak is polluted.

## Metrics to capture (every 60 s)

### catalog-publish-job
- JVM heap used % — slope per hour
- GC pause p99
- Thread count — slope per hour
- HikariCP connection pool used %
- Consumer offset lag for group `catalog-publish-group` on topic `discovr.publish.in.requests`
- Indexing pipeline step duration (parse, validate, persist)
- DLT count on `discovr.publish.es.dlt` (must remain 0)

### Postgres (discovery-infra-postgresql-primary)
- CPU utilization
- Memory working_set
- Active connections vs `max_connections`
- Slow query count (>500ms)
- WAL bytes generated (proxy for write volume)
- Replication lag to read replica (LSN bytes behind)

### Elasticsearch (discovery-elasticsearch-master)
- JVM heap used % — slope per hour
- GC pause p99
- Indexing rate (docs/sec on `beckn-catalog-groceryresource`)
- Search rate (must remain near 0 — no discover traffic)
- Refresh duration p99
- Pending tasks count
- Field data cache + filter cache evictions

### Kafka brokers (discovery-infra-kafka)
- Broker CPU + memory
- ISR shrinks
- Under-replicated partition count
- Bytes-in rate per broker (proxy for arrival rate)

### Cross-service
- `k8s.pod.network.io` on `catalog-publish-job` (bytes/sec inbound — should track JMeter pace)
- Pod restarts in any of the in-scope namespaces (must remain 0)

## SLOs
- Zero pod restarts across all in-scope namespaces
- Zero events on `discovr.publish.es.dlt`
- Consumer lag on `catalog-publish-group` recovers within 2 minutes of JMeter ramp-up completion
- ES heap drift ≤ 5 % per hour after first 30 min warmup
- catalog-publish-job heap drift ≤ 5 % per hour after first 30 min warmup
- catalog-publish-job thread count growth ≤ 10 per hour
- Postgres connection pool used % ≤ 80 % sustained
- Postgres replication lag ≤ 30 seconds sustained
- ES indexing p99 refresh duration ≤ 1 second
- discover-job CPU stays < 5 % of its limit (out-of-scope signal — non-zero implies polluted test)
- response-dispatcher network.io stays near 0 bytes/sec (same reason)

## Run procedure
1. **Confirm external trigger is starting**. The Catalg-side person tells you when they begin the soak (whatsapp / chat / shared status).
2. Agent runs **guardrails** (section 0 of the agent doc): cluster context, gcloud auth, GCP project, ClickStack reachable, namespaces allowlist.
3. Agent **does NOT call `jmeter-trigger.sh`** — `external_load: true` in this scenario's frontmatter tells the agent to skip the trigger step entirely. It only records "external trigger acknowledged at <timestamp>" in the report.
4. Agent **starts the 60-second polling loop** against ClickStack for the metrics listed above. Runs for `duration_min: 240` minutes.
5. Every hour, the agent computes drift slopes and writes a partial report so progress is visible mid-run.
6. After 4 hours, final report at `reliability/reports/15-<UTC-ts>/report.md` with the full SLO table + per-hour evidence + a `comments` field summarizing anything unusual.

## Report extras
- Attach **start + end snapshots** of `kubectl get pods -A` for the four in-scope namespaces (catches restarts the metrics might miss).
- Embed a hyperlink to the HyperDX dashboard's time window for this run (`Reliability Overview` with `from=<ts>&to=<ts>`).
- Note the Catalg-side run ID (so the two reports can be cross-referenced).

## Notes
- This scenario shares load with the Catalg-side scenario 03 (4-hour publish soak) and scenario 08 (git PVC growth). Run them on the same calendar window so a single soak yields both reports.
- If ES heap or PG connection pool blows past SLO mid-run, the discovr agent does NOT abort the soak — it just records the breach and continues. Only the Catalg-side agent can stop JMeter, and only the on-call human can decide to bail.
- Pod restart mid-run → scenario FAIL. Do not average around restarts.
