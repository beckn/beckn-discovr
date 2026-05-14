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

## 1. Current State Audit

### 1.1 Index Creation Lifecycle

Indices are created on-demand by `EsIndexManager` (catalog-publish-job). The lifecycle:

1. `ElasticIndexStep` calls `ensureIndex(indexName)` before each bulk operation
2. On the first call for any schema type, `ensureTemplateOnce()` creates a component template named `beckn-catalog-template`
3. If the index doesn't exist, it's created; if the alias doesn't exist, it's added
4. **H5 optimisation:** A `ConcurrentHashMap<String>` caches confirmed index names in-memory, skipping 2-4 HTTP round-trips on subsequent calls. Cleared only on JVM restart.

Index naming: `{prefix}-{schema-type}` where schema type is lowercased and non-alphanumeric characters replaced with hyphens. Example: `beckn-catalog-groceryitem`.

**Source:** `EsIndexManager.java:84-107`

### 1.2 Template Settings — Disk vs Built-in Fallback

The ES index template can be loaded from two sources:

| Source | Configuration | Trigger |
|--------|--------------|---------|
| Disk template | `config/es-index-template.json`, loaded via `app.catalog.elasticsearch.mapping.template-file` | Default when file exists and is readable |
| Built-in fallback | `EsIndexManager.defaultTemplateJson()` (Java text block, lines 153-268) | Disk file read fails (`IOException`) |

#### How the fallback works

`EsIndexManager.resolveTemplateJson()` (line 133-138) checks if a template file path is configured. If yes, it tries to read the file. If the file read fails (e.g., file missing, bad mount, permission error), it catches the `IOException`, logs a WARN, and falls back to the hardcoded Java text block:

```
event=es.template.load.failed path=<path> error=<message> reason=falling-back-to-default
```

**Source:** `EsIndexManager.java:133-151`

#### Why the fallback has drifted

The built-in fallback was written when the ES indexing feature was first implemented. Since then, the disk template (`es-index-template.json`) has evolved — synonyms were added, `dynamic: false` was set on `resource_attributes`, offer mappings were expanded, and `refresh_interval: 5s` was added. However, **nobody updated the Java text block to match**. The two templates are maintained independently, and there is no mechanism (test or build check) to detect when they diverge.

#### Current divergences

| # | Setting | Disk Template | Built-in Fallback | Risk |
|---|---------|--------------|-------------------|------|
| 1 | `index.refresh_interval` | `"5s"` | Not set (ES default: 1 s) | **HIGH** — bulk ingest creates 5x more segments under fallback |
| 2 | `resource_attributes.dynamic` | `false` | `true` | **HIGH** — fallback allows arbitrary fields to create mappings, risking mapping explosion |
| 3 | `offers` mapping | Full nested with 9 sub-properties (id, descriptor, provider, resourceIds, validity, availableTo, addOns, considerations, offerAttributes) | Bare `"type": "nested"` — no sub-properties | **MEDIUM** — offer-specific queries won't work correctly under fallback |
| 4 | Synonyms | File-based via `synonyms_path` (10 rules) | Inline array (9 rules — missing `"charging, charger"`) | **LOW** — one synonym missing; also file-based allows updates without redeployment |
| 5 | `index.mapping.total_fields.limit` | 2000 (from JSON) | 2000 (from AppProperties, configurable) | NONE — both default to 2000, but fallback reads from app config |
| 6 | `index.mapping.depth.limit` | 10 (from JSON) | 10 (from AppProperties, configurable) | NONE — same as above |

#### Options

| Option | Approach | Pros | Cons |
|--------|----------|------|------|
| **A: Sync the fallback** | Update the Java text block to match the disk template | Job still starts if the file is missing | Two copies to maintain — will drift again unless enforced by a test |
| **B: Fail fast** | Remove the fallback; throw an exception if the disk template can't be read | Single source of truth; impossible to drift | Job won't start without the template file — operators must fix the deployment |

**Recommendation:** Option B (fail fast). The disk template is always expected to be present — it's volume-mounted in Docker and would be deployed alongside the JAR in production. A startup failure is obvious and immediately actionable; a silent fallback that produces different indexing behaviour is not.

### 1.3 Analyzers and Synonyms

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

### 1.4 Bulk Indexing Configuration

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

### 1.5 Docker Local Dev Configuration

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

## 2. Findings

### 2.1 Shard Count and Sizing

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

### 2.2 Refresh Interval

**Current state:**

| Source | `refresh_interval` |
|--------|-------------------|
| Disk template | `5s` |
| Built-in fallback | Not set → ES default `1s` |
| Docker compose | Not overridden |

**Segment creation formula:**

```
segments created ≈ publish duration ÷ refresh interval
```

The actual publish duration depends on batch size, embedding generation (if enabled), ES bulk response time, and network latency. This will be measured during baseline capture (Section 5). Example for illustration:

| Scenario | Refresh interval | 10s publish | 30s publish | 60s publish |
|----------|-----------------|-------------|-------------|-------------|
| Disk template | 5 s | ~2 segments | ~6 segments | ~12 segments |
| Built-in fallback | 1 s | ~10 segments | ~30 segments | ~60 segments |
| Optimal (FULL replace) | -1 (disabled) + explicit refresh | 1 segment | 1 segment | 1 segment |

Each additional segment adds overhead at query time — Lucene must open file handles, query each segment, and merge results. For small indices this is milliseconds, but it compounds with concurrent searches.

**Recommendation:**

- Keep `5s` as the default (already configured in disk template)
- For FULL replace operations, consider setting `refresh_interval: -1` before bulk indexing and issuing an explicit `_refresh` after. This is a code change (Task #189).
- Fix the built-in fallback to include `refresh_interval: 5s` (Task #188).

### 2.3 Segments and Merge Policy (`index.merge.policy.*`)

**Current state:** Neither `es-index-template.json` nor `EsIndexManager.defaultTemplateJson()` sets any `index.merge.policy.*` settings. ES defaults apply:

| Setting | ES Default | Description |
|---------|-----------|-------------|
| `index.merge.policy.type` | `tiered` | Merge strategy |
| `index.merge.policy.max_merge_at_once` | 10 | Max segments merged in one pass |
| `index.merge.policy.segments_per_tier` | 10 | Target segments per tier before merge triggers |
| `index.merge.policy.floor_segment` | 2 MB | Segments below this are always eligible for merge |
| `index.merge.policy.max_merged_segment` | 5 GB | Cap on merged segment size |
| `index.merge.policy.deletes_pct_allowed` | 33% | Deleted doc % before forced merge |

**Analysis:** With index sizes under 100 MB, the tiered merge policy handles segment consolidation efficiently. The number of segments created per publish depends on duration and refresh interval (see formula in Section 2.2). For typical small publishes, ES will merge segments into 1-2 within seconds. The merge scheduler runs in the background and doesn't block indexing.

**When to revisit:** If segment count per shard consistently exceeds 30-50 (visible via `GET /_cat/segments/<index>?v`), consider:

- Setting `index.merge.policy.segments_per_tier` lower (e.g., 5) to trigger merges earlier
- Force-merging after bulk operations (`POST /<index>/_forcemerge?max_num_segments=1`)

**Recommendation:** No change needed at current data volumes. The ES defaults are appropriate for indices under 100 MB. These settings are live-updateable via `PUT /<index>/_settings` — no index recreation required. Monitor segment count as part of baseline metrics.

### 2.4 Field Mappings

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

**Recommendation:** Mappings are well-designed. The `dynamic: false` on `resource_attributes` in the disk template is correct and should be replicated in the built-in fallback (currently `dynamic: true` in fallback — see Finding 2.6 / divergence #2).

### 2.5 Compression Codec

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

### 2.6 Template Divergence (Critical)

This is the highest-priority finding. The built-in Java fallback (`EsIndexManager.defaultTemplateJson()`) was written once and never updated as the disk template evolved. A single file-read failure silently activates the stale fallback, changing indexing behaviour in 4 material ways:

1. **Segment creation rate** — fallback has no `refresh_interval`, so ES uses 1s default instead of 5s. This creates 5x more segments during bulk ingest.
2. **Mapping safety** — fallback has `resource_attributes.dynamic: true`, allowing arbitrary publisher fields to create ES mappings. The disk template correctly sets `false`.
3. **Offer queryability** — fallback has bare `"type": "nested"` on offers with no sub-properties. Offer-level queries won't match any fields.
4. **Synonym coverage** — fallback has 9 inline synonym rules; the disk file has 10. The `"charging, charger"` rule is missing from the Java text block (even though it was added to `es-synonyms.txt` in commit `e958e88`).

The root cause is maintaining two independent copies of the same template with no mechanism to detect drift. See Section 1.2 for full divergence table and options.

**Recommendation:** Remove the built-in fallback and fail fast if the disk template can't be read (Option B from Section 1.2). This eliminates the drift problem entirely. The disk template is always expected to be present in deployed environments (Task #190).

### 2.7 Index Creation Race Condition

**Current state:** `EsIndexManager.ensureIndex()` uses a `ConcurrentHashMap` (`confirmedIndexes`) to skip redundant index-existence checks. However, the check-then-create sequence is not atomic across threads. When two ES executor threads (`es-index-1`, `es-index-2`) simultaneously process catalogs of the same schema type for the first time, both see the index as absent and both attempt to create it. One succeeds; the other gets `resource_already_exists_exception`.

**Impact chain:**

1. `ensureIndex()` throws on the losing thread
2. `BulkIndexService.index()` catches the exception and marks **all docs in that batch** as failed (not just the index-creation call)
3. Failed docs are published to the failure topic (`discovr.publish.es.failures`)
4. `EsFailureConsumer` recovers them one-by-one (not in bulk)

**Observed in baseline capture (400 docs, 8 catalogs × 50 resources):**

| Metric | Value |
|--------|-------|
| Docs indexed via bulk | 350 (7 catalogs) |
| Docs failed due to race | 50 (1 catalog) |
| Recovery time (one-by-one) | ~3.5 s |
| Bulk indexing time (total) | ~13 s |
| Per-doc cost: bulk path | ~32 ms |
| Per-doc cost: recovery path | ~70 ms |

The recovery path is **~2x slower per doc** because `EsFailureConsumer` indexes individually rather than in bulk. In wall-clock terms, the race condition adds **~27% overhead** for this dataset. At production scale with larger catalogs (500+ resources per catalog), the one-by-one recovery becomes proportionally more expensive.

**Root cause:** The race condition is **deterministic** — it triggers on the very first publish to any new schema type whenever both executor threads pick up catalogs of the same type simultaneously. After the first creation, `confirmedIndexes` caches the index name, and subsequent calls skip the creation path entirely.

**Fix:** Either make `ensureIndex()` atomic (e.g., `putIfAbsent` guard before the HTTP call) or catch `resource_already_exists_exception` and treat it as success rather than propagating the exception. Both approaches are covered under Task #190.

**Source:** `EsIndexManager.java:93-107`, `BulkIndexService.java:99-104`

### 2.8 Missing ES Health Indicator

**Current state:** Neither job has an Elasticsearch-specific health indicator.

| Job | Health Indicator | Checks ES? |
|-----|-----------------|-----------|
| catalog-publish-job | `KafkaHealthIndicator` | No |
| catalog-discover-job | `DiscoveryHealthIndicator` | No (checks request stats only) |

**Impact:** ES cluster degradation (yellow/red status, thread pool rejections, circuit breaker trips, JVM heap pressure) is invisible via `/actuator/health`. Operators rely on direct ES API access (`GET /_cluster/health`) to detect issues.

**Recommendation:** Out of scope for Sprint 7, but noted for future work. Add an `ElasticsearchHealthIndicator` that checks cluster status and reports `DOWN` on red, `DEGRADED` on yellow.

---

## 3. Recommendations Summary

| # | Finding | Severity | Task | Recommendation |
|---|---------|----------|------|---------------|
| 1 | Template divergence (6 differences) — stale built-in fallback silently changes indexing behaviour | **HIGH** | #190 | Remove built-in fallback; fail fast if disk template can't be read (see Section 1.2, Option B) |
| 2 | Index creation race condition — deterministic failure on first publish to any new schema type | **MEDIUM** | #190 | Make `ensureIndex()` atomic or catch `resource_already_exists_exception` as success (see Section 2.7) |
| 3 | No bulk-time refresh disable | MEDIUM | #189 | Disable refresh during bulk, restore + flush after |
| 4 | No explicit shard/replica settings | LOW | #188 | Add `number_of_shards: 1`, `number_of_replicas: 1` (configurable) |
| 5 | No `index.merge.policy.*` settings | LOW | #189 | No change needed at current volumes; monitor segment count |
| 6 | No compression codec | LOW | #191 | Add `"index.codec": "best_compression"` |
| 7 | Unqueried text fields indexed | LOW | #190 | Add `"index": false` to `catalog_short_desc`, `catalog_long_desc` |
| 8 | No ES health indicator | LOW | Future | Add `ElasticsearchHealthIndicator` (out of scope for Sprint 7) |

---

## 4. Migration Notes

Changes to `number_of_shards`, `codec`, and `dense_vector.dims` require **index recreation** on existing deployments. These settings are fixed at index creation time and cannot be modified on live indices.

**Migration steps for existing deployments:**

1. Record baseline metrics (see `docs/analysis/es-baseline-metrics.json`)
2. Deploy updated template
3. Delete existing indices: `DELETE /beckn-catalog-*`
4. Re-publish all catalogs to trigger fresh index creation with new settings
5. Verify new settings applied: `GET /beckn-catalog-*/_settings`
6. Record post-migration metrics and compare

Changes to `refresh_interval`, `merge_policy`, and `number_of_replicas` **can be applied to live indices** via the `_settings` API without recreation.

---

## 5. Baseline Metrics

Baseline metrics (indexing throughput, document count, bulk duration, failure/recovery counts) were captured before any settings changes and are recorded in [`docs/analysis/es-baseline-metrics.json`](es-baseline-metrics.json).
