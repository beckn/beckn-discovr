# p99 capacity defaults — verification

Material defaults from the 100M architecture / profiling reports (GIN, ES shards,
Kafka concurrency, registry cache). Not harness-only.

## What changed

| Area | Default | Override |
|------|---------|----------|
| Prod Flyway GIN on `item.payload` | `V8__add_item_payload_gin.sql` → `idx_item_payload_gin` (`jsonb_path_ops`) | N/A (migration) |
| ES `catalogs-*` / `${INDEX_PREFIX}-*` template | **5** primary shards, **1** replica (Tier B starting plan) | Custom `ES_MAPPING_TEMPLATE_FILE` |
| Discover Kafka listener concurrency | **8** | `SPRING_KAFKA_LISTENER_CONCURRENCY` |
| Discover query pool | **16** workers | `DISCOVERY_PARALLEL_QUERY_WORKERS` (`0` → `min(2×CPU, 32)`) |
| Dispatcher Kafka listener concurrency | **4** | `SPRING_KAFKA_LISTENER_CONCURRENCY` |
| Registry / auth cache max keys | **10000** | `SIGNATURE_CACHE_MAX_KEYS` |

Keep listener concurrency **≤ topic partition count**. Raise partitions toward
24–48 before pushing concurrency higher (architecture §3.4).

### ES shard plan (100M)

- Target **~20–50 GB / primary shard**; cluster-wide **~80–200** primaries across
  per-schema `${INDEX_PREFIX}-*` indexes.
- Template default **5 / 1** is the per-index Tier B starting point. Reindex hot
  schema indexes with a higher shard count if average shard size exceeds ~50 GB.
- Tier A cost drills may use `number_of_replicas: 0`; Tier C may use `2`.
- Template applies to **new** indexes only — existing indexes keep their settings
  until reindex / rollover.

## How to verify

### 1. Unit / config tests

```bash
cd jobs/catalog-publish-job && ./gradlew test --tests '*ItemPayloadGinMigrationTest' --tests '*EsIndexTemplateShardPlanTest'
cd jobs/catalog-discover-job && ./gradlew test --tests '*AuthPropertiesTest' --tests '*QueryExecutorConfigTest' --tests '*CapacityDefaultsYamlTest'
cd jobs/response-dispatcher && ./gradlew test --tests '*ListenerConcurrencyDefaultsTest'
```

### 2. Flyway GIN on Postgres

After publish-job migrates (or apply V8 manually):

```sql
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'item' AND indexname = 'idx_item_payload_gin';

EXPLAIN (ANALYZE, BUFFERS)
SELECT id, catalog_id
FROM item
WHERE payload @@ '$.resourceAttributes.@type ? (@ == "GroceryItem")'::jsonpath
LIMIT 20;
```

Expect `indexdef` to mention `gin` + `jsonb_path_ops`, and `EXPLAIN` to use
`Bitmap Index Scan` / `idx_item_payload_gin` (not a sequential scan) on a
non-trivial corpus.

### 3. ES template settings

```bash
# After publish job ensures the template (or curl PUT the JSON with INDEX_PREFIX substituted)
curl -s "$ES_HOSTS/_index_template/beckn-catalog-template" \
  | jq '.index_templates[0].index_template.template.settings.index | {number_of_shards, number_of_replicas}'
```

New indexes should show `"number_of_shards": "5"` and `"number_of_replicas": "1"`.

### 4. Runtime concurrency / cache

```bash
# Discover
curl -s localhost:8082/actuator/env | jq '.. | objects | select(has("SPRING_KAFKA_LISTENER_CONCURRENCY") or has("discovery.postgresql.parallel-query-workers") or has("discovery.auth.cache-max-keys"))'

# Or confirm from logs at startup:
# event=QUERY_EXECUTOR_INIT workers=16
# response-dispatcher: event=KAFKA_CONSUMER_CONFIGURED concurrency=4
```

### 5. Load gates (optional)

- S2 / Kafka-in: lag p99 with discover concurrency 8 vs prior 3.
- S3: J-path p99 with vs without GIN; BM25 with 5-shard indexes.
- S1: ACK p99 cache-hit with `SIGNATURE_CACHE_MAX_KEYS=10000` under multi-BAP keys.
- S4: dispatcher concurrency 4 vs slow onix injection.
