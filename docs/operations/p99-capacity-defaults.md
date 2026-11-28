# p99 capacity defaults — verification

Material defaults from the 100M architecture / profiling reports (GIN, ES shards,
Kafka concurrency, registry cache). Not harness-only.

## What changed

| Area | Default | Override |
|------|---------|----------|
| Prod Flyway GIN on `item.payload` | `V8` → `idx_item_payload_gin` via transactional **`CREATE INDEX IF NOT EXISTS`** (`jsonb_path_ops`) | Pre-create with `CONCURRENTLY` on large live tables before migrate |
| ES single-node templates (`config/`, `discovr-deployment/elasticsearch/`, EsIndexManager built-in) | **1** shard / **0** replicas | Custom `ES_MAPPING_TEMPLATE_FILE` |
| ES Helm template (Tier B / multi-node) | **5** shards / **1** replica | Custom template / reindex |
| Discover Kafka listener concurrency | **8** | `SPRING_KAFKA_LISTENER_CONCURRENCY` |
| Discover query pool | **16** workers | `DISCOVERY_POSTGRESQL_PARALLEL_QUERY_WORKERS` (`0` → `min(2×CPU, 32)`) |
| Discover Hikari pool (docker) | **24** | `POSTGRES_MAX_POOL_SIZE` (≥ query workers) |
| Dispatcher Kafka listener concurrency | **4** | `SPRING_KAFKA_LISTENER_CONCURRENCY` |
| Registry / auth cache max keys | **10000** | `SIGNATURE_CACHE_MAX_KEYS` |
| Stack Kafka auto-create partitions | **8** (`KAFKA_CFG_NUM_PARTITIONS`) | Broker config / explicit topic |

Keep listener concurrency **≤ topic partition count**. Raise partitions toward
24–48 before pushing concurrency higher (architecture §3.4).

### ES shard plan (100M)

- Target **~20–50 GB / primary shard**; cluster-wide **~80–200** primaries across
  per-schema `${INDEX_PREFIX}-*` indexes.
- **Helm** template **5 / 1** is the per-index Tier B starting point — use only when
  the ES cluster has **≥ 2 data nodes** (otherwise indexes stay yellow).
- **Compose / discovr-stack** templates stay **1 / 0** for single-node 512 MB ES.
- Reindex hot schema indexes with a higher shard count if average shard size
  exceeds ~50 GB. Template applies to **new** indexes only.

### Pool coupling

`discovery.postgresql.parallel-query-workers` must stay **≤** Hikari
`maximum-pool-size`. Extra workers block on `getConnection` (up to connection
timeout) past the query timeout; cancel does not stop a running JDBC query.

### GIN scope

`jsonb_path_ops` accelerates `@@` / `@?` JSONPath predicates. It does **not**
cover RFC 9535 typed subquery predicates (#489) — measure that path separately.

## How to verify

### 1. Unit / config tests

```bash
cd jobs/catalog-publish-job && ./gradlew test --tests '*ItemPayloadGinMigrationTest' --tests '*EsIndexTemplateShardPlanTest' --tests '*AuthPropertiesTest'
cd jobs/catalog-discover-job && ./gradlew test --tests '*AuthPropertiesTest' --tests '*QueryExecutorConfigTest' --tests '*CapacityDefaultsYamlTest'
cd jobs/response-dispatcher && ./gradlew test --tests '*ListenerConcurrencyDefaultsTest'
```

### 2. Flyway GIN on Postgres

After publish-job migrates (or apply V8 manually):

```sql
SELECT indexname, indexdef, indisvalid
FROM pg_indexes
JOIN pg_class c ON c.relname = indexname
JOIN pg_index i ON i.indexrelid = c.oid
WHERE tablename = 'item' AND indexname = 'idx_item_payload_gin';

EXPLAIN (ANALYZE, BUFFERS)
SELECT id, catalog_id
FROM item
WHERE payload @@ '$.resourceAttributes.@type ? (@ == "GroceryItem")'::jsonpath
LIMIT 20;
```

Expect `indisvalid = true`, `indexdef` mentioning `gin` + `jsonb_path_ops`,
and `EXPLAIN` using `Bitmap Index Scan` / `idx_item_payload_gin` on a
non-trivial corpus.

**Large live tables:** V8 uses a transactional `CREATE INDEX` (Flyway-safe;
avoids mixed-statement errors and startup-probe timeouts). Pre-create with
`CREATE INDEX CONCURRENTLY IF NOT EXISTS ...` in a maintenance window before
rolling the job so Flyway no-ops. If an INVALID leftover exists from a prior
manual concurrent build: `DROP INDEX CONCURRENTLY IF EXISTS idx_item_payload_gin`
then recreate.

### 3. ES template settings

```bash
curl -s "$ES_HOSTS/_index_template/beckn-catalog-template" \
  | jq '.index_templates[0].index_template.template.settings.index | {number_of_shards, number_of_replicas}'
```

- Single-node Compose/stack: `"1"` / `"0"`.
- Helm multi-node: `"5"` / `"1"`.

### 4. Runtime concurrency / cache / pool

Confirm from **startup logs** (docker profile does not expose `/actuator/env`):

```text
event=queryExecutor.init workers=16
# response-dispatcher:
event=KAFKA_CONSUMER_CONFIGURED concurrency=4
```

Also confirm broker partition count ≥ listener concurrency:

```bash
kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --topic discovr.discover.in.requests
```

### 5. Load gates (optional)

- S2 / Kafka-in: lag p99 with discover concurrency 8 vs prior 3 (with ≥8 partitions).
- S3: J-path p99 with vs without GIN; BM25 shard plan as deployed.
- S1: ACK p99 cache-hit with `SIGNATURE_CACHE_MAX_KEYS=10000` under multi-BAP keys.
- S4: dispatcher concurrency 4 vs slow onix injection.
