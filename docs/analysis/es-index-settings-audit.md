# Elasticsearch Index Settings Audit

| Field | Value |
|-------|-------|
| Date | 2026-05-14 |
| Sprint | 7 |
| Story | #185 — Analyse and tune Elasticsearch index settings |
| Task | #186 — Written analysis of current ES index settings |
| Scope | catalog-publish-job (indexing), catalog-discover-job (querying) |

---

## Executive Summary

This document audits the Elasticsearch index configuration across the Beckn Discovr stack, covering shard sizing, refresh interval, field mappings, segment/merge policy, and compression codec — the five areas identified in Story #185.

**Key findings:**

- **Template drift** — The disk template (`config/es-index-template.json`) and the built-in Java fallback (`EsIndexManager.defaultTemplateJson()`) have diverged in 6 material ways, including synonym coverage, `resource_attributes` dynamic mapping, and offer sub-properties. A silent fallback to the built-in template changes indexing behaviour with no alert beyond a WARN log.
- **Shard sizing is appropriate** — Current data volumes (well under 100 MB per schema-type index) are far below the 10-50 GB per shard guideline. The ES default of 1 primary shard is optimal for the deployment profile.
- **Refresh interval has a fallback gap** — The disk template sets `refresh_interval: 5s` (good for bulk ingest), but the built-in fallback has no setting, falling back to the ES default of 1 s.
- **No ES cluster health indicator** — Both jobs have health indicators, but none check ES cluster status. Cluster degradation (yellow/red, thread pool rejections, heap pressure) is invisible via `/actuator/health`.

---

## 1. ES Concepts Primer

### Storage Hierarchy

```
Cluster
  └── Node (JVM process)
        └── Index (logical namespace, e.g. "beckn-catalog-groceryitem")
              └── Shard (Lucene index, the unit of parallelism)
                    └── Segment (immutable Lucene file, created on each refresh)
                          └── Document (one ES JSON document)
```

### Refresh Cycle

Every `refresh_interval` (default 1 s), Elasticsearch takes in-memory buffered documents and writes them to a new **segment** on disk. The segment is immediately searchable. More frequent refreshes mean more segments, which means more file handles and more work at query time (Lucene must merge results from every segment in a shard).

### Merge Policy

Elasticsearch periodically merges smaller segments into larger ones to keep segment count manageable. The default tiered merge policy targets:

- 10 segments per tier
- Floor segment size: 2 MB
- Max merged segment: 5 GB

For small indices (< 100 MB), merges are fast and infrequent. For large indices, an aggressive refresh interval (1 s) during bulk ingest can outpace the merge scheduler and cause high segment counts.

### Compression Codecs

| Property | LZ4 (default) | DEFLATE (best_compression) |
|----------|--------------|---------------------------|
| Write speed | Baseline | ~15-30% slower |
| Read speed | Baseline | ~5-10% slower |
| Compression ratio | ~2-3x | ~3-4x (15-25% smaller than LZ4) |
| CPU overhead | Low | Moderate |
| Best for | Read-heavy, low-latency | Archive indices, storage-constrained |

---

## 2. Current State Audit

### 2.1 Index Creation Lifecycle

Indices are created on-demand by `EsIndexManager` (catalog-publish-job). The lifecycle:

1. `ElasticIndexStep` calls `ensureIndex(indexName)` before each bulk operation
2. On the first call for any schema type, `ensureTemplateOnce()` creates a component template named `beckn-catalog-template`
3. If the index doesn't exist, it's created; if the alias doesn't exist, it's added
4. **H5 optimisation:** A `ConcurrentHashMap<String>` caches confirmed index names in-memory, skipping 2-4 HTTP round-trips on subsequent calls. Cleared only on JVM restart.

Index naming: `{prefix}-{schema-type}` where schema type is lowercased and non-alphanumeric characters replaced with hyphens. Example: `beckn-catalog-groceryitem`.

**Source:** `EsIndexManager.java:84-107`

### 2.2 Template Settings — Disk vs Built-in Fallback

The template can be loaded from two sources:

| Source | Configuration | Trigger |
|--------|--------------|---------|
| Disk template | `config/es-index-template.json`, loaded via `app.catalog.elasticsearch.mapping.template-file` | Default when file exists and is readable |
| Built-in fallback | `EsIndexManager.defaultTemplateJson()` (Java text block, lines 153-268) | Disk file read fails (`IOException`) |

When the disk template fails to load, the only signal is a WARN log:

```
event=es.template.load.failed path=<path> error=<message> reason=falling-back-to-default
```

**Source:** `EsIndexManager.java:140-151`, `LogEvent.java:66`

#### Divergences Between Disk and Fallback

| # | Setting | Disk Template | Built-in Fallback | Risk |
|---|---------|--------------|-------------------|------|
| 1 | `index.refresh_interval` | `"5s"` | Not set (ES default: 1 s) | **HIGH** — bulk ingest creates 5x more segments under fallback |
| 2 | `resource_attributes.dynamic` | `false` | `true` | **HIGH** — fallback allows arbitrary fields to create mappings, risking mapping explosion |
| 3 | `offers` mapping | Full nested with 9 sub-properties (id, descriptor, provider, resourceIds, validity, availableTo, addOns, considerations, offerAttributes) | Bare `"type": "nested"` — no sub-properties | **MEDIUM** — offer-specific queries won't work correctly under fallback |
| 4 | Synonyms | File-based via `synonyms_path` (10 rules) | Inline array (9 rules — missing `"charging, charger"`) | **LOW** — one synonym missing; also file-based allows updates without redeployment |
| 5 | `index.mapping.total_fields.limit` | 2000 (from JSON) | 2000 (from AppProperties, configurable) | NONE — both default to 2000, but fallback reads from app config |
| 6 | `index.mapping.depth.limit` | 10 (from JSON) | 10 (from AppProperties, configurable) | NONE — same as above |

### 2.3 Analyzers and Synonyms

Two custom analyzers are defined:

| Analyzer | Applied at | Filter chain |
|----------|-----------|-------------|
| `beckn_text` | Index time (`full_text_blob` field) | `lowercase` → `english_stop` → `english_stemmer` |
| `beckn_text_search` | Search time (`full_text_blob` field) | `lowercase` → `english_stop` → `beckn_synonyms` → `english_stemmer` |

Synonym expansion happens at search time only (via `search_analyzer`), which means:

- Adding new synonyms doesn't require reindexing
- The synonym filter must be in the search analyzer, not the index analyzer

#### Full Synonym Inventory (`config/es-synonyms.txt`)

| Domain | Rule | Expansion |
|--------|------|-----------|
| EV / Electric Vehicle | `ev, electric vehicle` | ev ↔ electric vehicle |
| EV / Electric Vehicle | `charging, charger` | charging ↔ charger |
| EV / Electric Vehicle | `charger, charging station` | charger ↔ charging station |
| EV / Electric Vehicle | `kw, kilowatt` | kw ↔ kilowatt |
| Logistics | `logistics, shipping, delivery` | logistics ↔ shipping ↔ delivery |
| Logistics | `parcel, package, shipment` | parcel ↔ package ↔ shipment |
| Food | `organic, natural` | organic ↔ natural |
| Food | `vegan, plant-based` | vegan ↔ plant-based |
| General Commerce | `discount, offer, deal` | discount ↔ offer ↔ deal |
| General Commerce | `cod, cash delivery` | cod ↔ cash delivery |

10 rules across 4 domains. The built-in fallback has 9 inline rules — `"charging, charger"` is missing.

### 2.4 Bulk Indexing Configuration

| Setting | Value | Source |
|---------|-------|--------|
| Batch size | 100 | `APP_CATALOG_ELASTICSEARCH_BULK_BATCH_SIZE` |
| Retry attempts | 3 | `@Retryable(maxAttempts = 3)` in `BulkIndexService` |
| Retry backoff | 1 s initial, 2x multiplier, 30 s max | `@Backoff(delay = 1000, multiplier = 2, maxDelay = 30000)` |
| Retryable exceptions | `ConnectException`, `SocketTimeoutException`, `ElasticsearchException` | `BulkIndexService.java:89-93` |
| Executor pool size | 2 | `APP_CATALOG_ELASTICSEARCH_POOL_SIZE` |
| Executor queue capacity | 200 | `APP_CATALOG_ELASTICSEARCH_POOL_QUEUE_CAPACITY` |
| Max failure attempts | 5 | `APP_CATALOG_ELASTICSEARCH_MAX_FAILURE_ATTEMPTS` |
| Failure topic | `discovr.publish.es.failures` | `APP_CATALOG_ELASTICSEARCH_FAILURE_TOPIC` |
| Dead letter topic | `discovr.publish.es.dlt` | `APP_CATALOG_ELASTICSEARCH_FINAL_DLQ_TOPIC` |

**Source:** `docker-compose.yml:129-145`, `BulkIndexService.java:89-92`

### 2.5 Docker Local Dev Configuration

| Setting | Value |
|---------|-------|
| ES version | 9.3.1 |
| Discovery mode | `single-node` |
| Heap | `-Xms512m -Xmx512m` |
| Security | Disabled (`xpack.security.enabled=false`) |
| Synonyms file | Volume-mounted: `./config/es-synonyms.txt` → `/usr/share/elasticsearch/config/config/es-synonyms.txt` |
| `ES_MIN_SCORE` | 0.68 (overrides `application.yml` default of 0.72) |

The `min_score` override means local dev returns more results (lower threshold) than production defaults would. This is intentional for development, but should be documented so test results are understood in context.

**Source:** `docker-compose.yml:106-127`, `application.yml:139` (catalog-discover-job)

---

## 3. Findings

### 3.1 Shard Count and Sizing

**Current state:** No explicit `number_of_shards` or `number_of_replicas` in either template. ES defaults apply: 1 primary shard, 1 replica.

**Sizing analysis:**

| Dimension | Estimate |
|-----------|----------|
| Resources per schema type | 1,000 - 10,000 (typical) |
| ES document size | ~2-5 KB per resource (JSON with attributes) |
| Index size per schema type | ~5-50 MB |
| Total across 10 schema types | ~50-500 MB |
| Shard guideline | 10-50 GB per shard |

Current data volumes are **100-1000x below** the shard size guideline. A single shard per index is optimal.

**CPU context:** ES uses 1 thread per shard per search request. With 1 shard per index, search parallelism = 1 (or 2 if replicas are present). For Docker dev with 2 CPUs, this is appropriate. For production with more CPUs, parallelism comes from concurrent requests across different indices, not from shard count within a single index.

**Recommendation:** No change needed. Current defaults are appropriate for the data volumes. If any single schema-type index grows past 10 GB, consider splitting — but this is unlikely in the foreseeable future.

### 3.2 Refresh Interval

**Current state:**

| Source | `refresh_interval` |
|--------|-------------------|
| Disk template | `5s` |
| Built-in fallback | Not set → ES default `1s` |
| Docker compose | Not overridden |

**Segment creation analysis** for a typical catalog publish (30 s duration):

| Scenario | Refresh interval | Segments created | Search overhead |
|----------|-----------------|-----------------|----------------|
| Disk template | 5 s | ~6 | Low |
| Built-in fallback | 1 s | ~30 | Moderate |
| Optimal (FULL replace) | -1 (disabled) + explicit refresh | 1 | Minimal |

Each additional segment adds overhead at query time — Lucene must open file handles, query each segment, and merge results. For small indices this is milliseconds, but it compounds with concurrent searches.

**Recommendation:**

- Keep `5s` as the default (already configured in disk template)
- For FULL replace operations, consider setting `refresh_interval: -1` before bulk indexing and issuing an explicit `_refresh` after. This is a code change (Task #189).
- Fix the built-in fallback to include `refresh_interval: 5s` (Task #188).

### 3.3 Segments and Merge Policy

**Current state:** No explicit merge policy settings. ES defaults apply:

| Setting | ES Default |
|---------|-----------|
| Merge policy | `tiered` |
| `max_merge_at_once` | 10 |
| `segments_per_tier` | 10 |
| `floor_segment` | 2 MB |
| `max_merged_segment` | 5 GB |
| `deletes_pct_allowed` | 33% |

**Analysis:** With index sizes under 100 MB, the tiered merge policy handles segment consolidation efficiently. After a bulk publish creating ~6 segments (at `refresh_interval: 5s`), ES will merge these into 1-2 segments within seconds. The merge scheduler runs in the background and doesn't block indexing.

**When to revisit:** If segment count per shard consistently exceeds 30-50 (visible via `GET /_cat/segments/<index>?v`), consider:

- Lowering `segments_per_tier` to trigger merges earlier
- Force-merging after bulk operations (`POST /<index>/_forcemerge?max_num_segments=1`)

**Recommendation:** No change needed. Monitor segment count as part of baseline metrics. Force-merge is inappropriate for actively-indexed indices — only use on read-only archive indices.

### 3.4 Field Mappings

The disk template (`config/es-index-template.json`) defines:

**7 dynamic templates** (applied to unmapped fields):

| Template | Path match | Source type | Mapped as |
|----------|-----------|-------------|-----------|
| `geo_fields` | `*.geo` | any | `geo_shape` |
| `resource_attrs_longs_as_float` | `resource_attributes.*` | long | `float` |
| `resource_attrs_doubles_as_float` | `resource_attributes.*` | double | `float` |
| `strings_as_keywords` | any | string | `keyword` |
| `integers_as_ints` | any | long | `integer` |
| `doubles_as_doubles` | any | double | `double` |
| `booleans` | any | boolean | `boolean` |

**Key design decisions:**

- `resource_attributes` is `"dynamic": false` in the disk template — only `@context` and `@type` are explicitly mapped. All other resource attribute fields are silently ignored by ES (not indexed, not searchable). This prevents mapping explosion from arbitrary publisher data.
- `offers` is `"type": "nested"` with `"dynamic": false` and 9 explicit sub-properties. Nested type enables independent querying of individual offers within a document.
- `full_text_blob` uses split analyzers: `beckn_text` at index time, `beckn_text_search` at search time (with synonyms).
- `resource_vector` is `dense_vector` with 1536 dimensions (OpenAI embedding size), cosine similarity, indexed for approximate kNN search.

**Nested types** (require special query syntax): `offers`, `constraints`, `policies`, `catalog_descriptor_docs`, `catalog_descriptor_media_file`, `resource_descriptor_docs`, `resource_descriptor_media_file`.

**Recommendation:** Mappings are well-designed. The `dynamic: false` on `resource_attributes` in the disk template is correct and should be replicated in the built-in fallback (currently `dynamic: true` in fallback — see Finding 3.6 / divergence #2).

### 3.5 Compression Codec

**Current state:** No codec specified in either template. ES default (LZ4) applies.

**Analysis:**

| Factor | Assessment |
|--------|-----------|
| Current index size | < 100 MB per index |
| Storage savings from DEFLATE | ~15-25% smaller (15-25 MB saved across all indices) |
| Write speed impact | ~15-30% slower per bulk operation |
| Read speed impact | ~5-10% slower (mitigated by OS page cache) |
| Operational complexity | Setting is immutable after index creation — requires recreation to change |

At current data volumes, the storage savings from `best_compression` are negligible (~25 MB total). The write speed penalty is also small in absolute terms (milliseconds on small indices). However, the setting is **immutable after index creation**, so changing it later requires deleting and recreating all indices.

**Recommendation:** Add `"index.codec": "best_compression"` to both templates. The write-once-read-many access pattern (bulk publish → infrequent discover queries) favours compression. Make configurable via `AppProperties.Mapping.codec` for operators who need raw indexing speed.

### 3.6 Template Divergence (Critical)

This is the highest-priority finding. The 6 divergences documented in Section 2.2 mean that a single file-read failure silently changes:

1. **Segment creation rate** (1s vs 5s refresh) — 5x more segments during bulk ingest
2. **Mapping safety** (`dynamic: true` on `resource_attributes`) — arbitrary publisher fields create ES mappings
3. **Offer queryability** (bare nested vs explicit sub-properties) — offer-level filters won't match
4. **Synonym coverage** (9 vs 10 rules) — "charging" ↔ "charger" synonym missing

**Recommendation:** Synchronize the built-in fallback with the disk template (Task #190). Specifically:

- Add `"index.refresh_interval": "5s"` to built-in settings
- Change `resource_attributes.dynamic` from `true` to `false`
- Add full `offers` sub-property mappings
- Add the missing `"charging, charger"` synonym

### 3.7 Missing ES Health Indicator

**Current state:** Neither job has an Elasticsearch-specific health indicator.

| Job | Health Indicator | Checks ES? |
|-----|-----------------|-----------|
| catalog-publish-job | `KafkaHealthIndicator` | No |
| catalog-discover-job | `DiscoveryHealthIndicator` | No (checks request stats only) |

**Impact:** ES cluster degradation (yellow/red status, thread pool rejections, circuit breaker trips, JVM heap pressure) is invisible via `/actuator/health`. Operators rely on direct ES API access (`GET /_cluster/health`) to detect issues.

**Recommendation:** Out of scope for Sprint 7, but noted for future work. Add an `ElasticsearchHealthIndicator` that checks cluster status and reports `DOWN` on red, `DEGRADED` on yellow.

---

## 4. Already Optimised (No Action Needed)

The following items from the April 30 performance review have been verified as **already fixed** in the codebase:

| Finding | Status | Evidence |
|---------|--------|----------|
| H1: Full `_source` returned | FIXED | All 4 query paths exclude `full_text_blob`, `resource_vector`, `indexed_at` |
| H4: Geo-shape in `bool.must` | FIXED | Geo queries now in `bool.filter` (`ElasticsearchQueryEngine.java:190-191`) |
| H5: `ensureIndex()` 2-4 HTTP calls per batch | FIXED | `ConcurrentHashSet` cache (`EsIndexManager.java:66`) |
| H6: Serial embedding per item | FIXED | `CompletableFuture.allOf()` parallel embedding (`ElasticIndexStep.java:142-162`) |
| H7: `buildTextSearchJson()` at INFO level | FIXED | Moved to DEBUG with lazy `Supplier` (`ElasticsearchTextSearchEngine.java:179-182`) |
| M1: ES connection pool (discover-job) | FIXED | 20/40 in `EsSearchConfig.java` |
| M2: `trackTotalHits` not disabled | FIXED | `trackTotalHits(false)` on all query paths |
| M6: No `refresh_interval` in disk template | FIXED | `5s` in `es-index-template.json:7` |
| L1: `resource_attributes` dynamic:true (disk) | FIXED | `dynamic: false` in disk template |
| C4: HTTP 429 not retried | FIXED | `EsRateLimitException` wrapping in `BulkIndexService.java:113-119` |

---

## 5. Field Mapping Audit

### 5.1 Fields Queried by Discover-Job

| ES Field | Query Path | Query Type | Boost |
|----------|-----------|------------|-------|
| `full_text_blob` | BM25 keyword | `match` (Operator.And, must) | 1.0 |
| `resource_name` | BM25 keyword | `multi_match` (should) | 2.0 |
| `catalog_name` | BM25 keyword | `multi_match` (should) | 2.0 |
| `resource_provider_name` | BM25 keyword | `multi_match` (should) | 1.5 |
| `resource_vector` | KNN semantic | `knn` query | — |
| `resource_attributes_context` | Schema filter | `term` (filter) | — |
| `resource_attributes_type` | Schema filter | `term` (filter) | — |
| `loc_*.geo` | Spatial | `geo_shape` (filter) | — |

### 5.2 Fields Read from `_source` by EsSearchAssembler

**Catalog-level:** `catalog_id`, `catalog_name`, `catalog_short_desc`, `catalog_long_desc`, `catalog_descriptor_thumbnail_image`, `catalog_descriptor_docs`, `catalog_descriptor_media_file`, `catalog_provider_id`, `catalog_provider_name`, `catalog_validity`, `catalog_is_active`

**Resource-level:** `resource_id`, `resource_name`, `resource_short_desc`, `resource_long_desc`, `resource_descriptor_thumbnail_image`, `resource_descriptor_docs`, `resource_descriptor_media_file`, `resource_category_code`, `resource_category_name`, `resource_rating_value`, `resource_rating_count`, `resource_rating_review_text`, `resource_rateable`, `resource_is_active`, `resource_provider_id`, `resource_provider_name`, `resource_attributes`, `resource_attributes_type`, `resource_attributes_context`

**Structured:** `constraints`, `policies`, `offers`, `loc_*` (location fields)

### 5.3 Fields Excluded from `_source`

`full_text_blob`, `resource_vector`, `indexed_at` — excluded via source filtering on all query paths.

### 5.4 Fields Indexed but Never Queried

| Field | Type | Indexed? | Queried? | Recommendation |
|-------|------|----------|----------|---------------|
| `catalog_short_desc` | text | Yes | No | Set `"index": false` |
| `catalog_long_desc` | text | Yes | No | Set `"index": false` |
| `catalog_context` | keyword | Yes | No | Keep (low overhead) |
| `catalog_type` | keyword | Yes | No | Keep (low overhead) |
| `resource_context` | keyword | Yes | No | Keep (low overhead) |
| `resource_type` | keyword | Yes | No | Keep (low overhead) |
| `resource_short_desc` | text | Yes | No* | Keep (potential future search field) |
| `resource_long_desc` | text | Yes | No* | Keep (potential future search field) |
| `schema_type` | keyword | Yes | No | Keep (used for index routing) |
| `network_id` | keyword | Yes | No** | Keep (potential future filter) |

\* Resource descriptions contribute to `full_text_blob` which IS queried. Keeping them indexed provides direct field-level search capability.

\** `network_id` is a likely future filter candidate for multi-network deployments.

---

## 6. Baseline Metrics Procedure

Before applying any optimisation, record the following baseline metrics from a representative deployment.

### 6.1 Index-Level Metrics

```bash
# Index size and document count
curl -s 'localhost:9200/_cat/indices/beckn-catalog-*?v&h=index,docs.count,store.size,pri.store.size'

# Segment count per index
curl -s 'localhost:9200/_cat/segments/beckn-catalog-*?v&h=index,shard,segment,generation,docs.count,size'

# Index settings (verify applied template)
curl -s 'localhost:9200/beckn-catalog-*/_settings?pretty'

# Mapping field count
curl -s 'localhost:9200/beckn-catalog-*/_mapping?pretty' | python3 -c "
import json, sys
data = json.load(sys.stdin)
for idx, v in data.items():
    props = v.get('mappings', {}).get('properties', {})
    print(f'{idx}: {len(props)} top-level fields')
"
```

### 6.2 Cluster-Level Metrics

```bash
# Cluster health
curl -s 'localhost:9200/_cluster/health?pretty'

# Node stats (indexing rate, search latency, merge stats)
curl -s 'localhost:9200/_nodes/stats/indices?pretty' | python3 -c "
import json, sys
data = json.load(sys.stdin)
for nid, node in data.get('nodes', {}).items():
    idx = node.get('indices', {})
    print(f\"Node: {node.get('name')}\")
    print(f\"  Indexing: {idx.get('indexing', {}).get('index_total', 0)} docs, {idx.get('indexing', {}).get('index_time_in_millis', 0)}ms total\")
    print(f\"  Search:   {idx.get('search', {}).get('query_total', 0)} queries, {idx.get('search', {}).get('query_time_in_millis', 0)}ms total\")
    print(f\"  Merges:   {idx.get('merges', {}).get('total', 0)} merges, {idx.get('merges', {}).get('total_size_in_bytes', 0)} bytes\")
    print(f\"  Refresh:  {idx.get('refresh', {}).get('total', 0)} refreshes, {idx.get('refresh', {}).get('total_time_in_millis', 0)}ms total\")
"
```

### 6.3 Application-Level Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `discovr.publish.es.resource.indexed` | Counter | Resources successfully indexed |
| `discovr.publish.es.resource.failure` | Counter | Per-resource bulk failures |
| `discovr.publish.es.batch.failure` | Counter | Full batch-level failures |
| `discovr.publish.es.retry` | Counter | Bulk retry attempts |
| `discovr.publish.es.recovered` | Counter | Resources recovered by failure consumer |
| `discovr.publish.es.permanent.failure` | Counter | Resources exceeding max retry attempts |
| `discovr.publish.es.index.created` | Counter | ES indices created |
| `discovr.publish.es.bulk.duration` | Timer | Bulk request latency (P50/P95/P99) |
| `discovr.publish.es.failures.pending` | Gauge | Resources queued in failure topic |

### 6.4 Benchmark Procedure

1. Start fresh Docker stack: `docker compose down -v && docker compose up -d`
2. Wait for ES to be ready: `curl -s localhost:9200/_cluster/health?wait_for_status=green&timeout=30s`
3. Publish a representative catalog payload (e.g., 500 resources across 3 schema types)
4. Record: total indexing time, docs/sec, index size, segment count
5. Run 10 discover queries with text search; record P50/P95 latency
6. Save results as baseline in `docs/analysis/es-baseline-metrics.json`

---

## 7. Recommendations Summary

| # | Finding | Severity | Task | Recommendation |
|---|---------|----------|------|---------------|
| 1 | Template divergence (6 differences) | **HIGH** | #190 | Sync built-in fallback with disk template |
| 2 | No `refresh_interval` in built-in fallback | **HIGH** | #189 | Add `5s` to built-in (sync with disk) |
| 3 | `resource_attributes.dynamic: true` in fallback | **HIGH** | #190 | Change to `false` (sync with disk) |
| 4 | No bulk-time refresh disable | MEDIUM | #189 | Disable refresh during bulk, restore + flush after |
| 5 | Missing synonym in built-in fallback | LOW | #190 | Add `"charging, charger"` to inline list |
| 6 | No explicit shard/replica settings | LOW | #188 | Add `number_of_shards: 1`, `number_of_replicas: 1` (configurable) |
| 7 | No merge policy settings | LOW | #189 | Monitor segment count; no change needed at current volumes |
| 8 | No compression codec | LOW | #191 | Add `"index.codec": "best_compression"` |
| 9 | Unqueried text fields indexed | LOW | #190 | Add `"index": false` to `catalog_short_desc`, `catalog_long_desc` |
| 10 | No ES health indicator | LOW | Future | Add `ElasticsearchHealthIndicator` (out of scope for Sprint 7) |

---

## 8. Migration Notes

Changes to `number_of_shards`, `codec`, and `dense_vector.dims` require **index recreation** on existing deployments. These settings are fixed at index creation time and cannot be modified on live indices.

**Migration steps for existing deployments:**

1. Record baseline metrics (Section 6)
2. Deploy updated template
3. Delete existing indices: `DELETE /beckn-catalog-*`
4. Re-publish all catalogs to trigger fresh index creation with new settings
5. Verify new settings applied: `GET /beckn-catalog-*/_settings`
6. Record post-migration metrics and compare

Changes to `refresh_interval`, `merge_policy`, and `number_of_replicas` **can be applied to live indices** via the `_settings` API without recreation.
