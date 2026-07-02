# Unified Performance Review — catalog-discover-job + ES High-Throughput Analysis

**Date:** 2026-04-30 (updated 2026-05-04 with ES text search deep-dive + GET API analysis)
**Sources:**
- `docs/perf-review-catalog-discover-job-2026-04-30-1122.md` — senior engineer profiling (all paths)
- `docs/elasticsearch-highthroughput-research.md` — Opus 4.6 research against ES best-practice corpus
- Deep-dive code review of `ElasticsearchTextSearchEngine`, `ElasticsearchQueryEngine`, `EsSchemaFilterBuilder`, `EmbeddingClient`, `QueryEnricher`, `EsSpatialQueryBuilder`, `EsSearchAssembler`, `DiscoveryController`, `DiscoveryService`, `application.yml`

---

## Executive Summary

The catalog-discover-job routes `discover` requests across four query paths (PostgreSQL JSONPath, PostGIS spatial, ES BM25 keyword, ES semantic knn). The service has good structural foundations: correct use of the modern ES Java API client, proper `filter` vs `must` separation on the keyword path, Micrometer metrics, and a Kafka-based failure recovery pipeline. However, five issues currently impose hard throughput ceilings that limit the service to well below its potential:

| # | Issue | Impact |
|---|-------|--------|
| 1 | `kafkaTemplate.send().get(30s)` blocks all consumer threads | Zero throughput for up to 30s on any broker hiccup |
| 2 | Two serial blocking HTTP calls before ES knn query | ~3 req/s max on semantic path; full executor starvation |
| 3 | `POSTGRES_LOG_EXPLAIN_ANALYZE=true` default | Every PG query runs **twice** in production |
| 4 | HTTP 429 not retried in BulkIndexService | Back-pressure from ES silently routes documents to DLT |
| 5 | Full `_source` returned on every ES search | 50–80% unnecessary network payload per hit |

The immediate zero-code fix: **set `POSTGRES_LOG_EXPLAIN_ANALYZE=false`** in your deployment environment right now.

> **2026-05-04 update:** Deep-dive ES text search and GET API analysis added **1 new CRITICAL**, **4 new HIGH**, **4 new MEDIUM**, and **2 new LOW** findings. See [ES Text Search Gaps](#es-text-search-gaps) and [GET API Gaps](#get-api-gaps) sections.

---

## Throughput Model

### Path A — PostgreSQL JSONPath + PostGIS spatial
Theoretical max ≈ **120 req/s** (10 Hikari connections ÷ 20ms avg × 3 consumer threads), bounded by blocking Kafka publish (Step 7).

### Path B — PostgreSQL JSONPath only
Bounded by GIN index availability on `item.payload`. With GIN: low-ms per query. Without GIN: full table scan on `item.payload` JSONB at scale.

### Path C — Spatial only
Bounded by GIST index on `item_location_collection.geom`. Correlated `EXISTS` subquery scales linearly with table size if index is not selective.

### Path D — ES keyword (native-els)
ES BM25 query: 5–300ms. Throughput ≈ **3 consumer threads × (1 / 0.1s) = 30 req/s** under median ES latency, limited by blocking Kafka publish.

### Path D — ES semantic (els-semantic-search)
| Step | Latency |
|------|---------|
| LLM enrichment (`QueryEnricher`) | 100ms–30s (blocking) |
| Embedding (`EmbeddingClient`, 3 retries) | 50ms–21s worst case |
| ES knn query (numCandidates=500) | 50–500ms |
| **Theoretical max** | **≈ 3 req/s** at typical LLM latencies, **→ 0** during LLM degradation |

---

## Findings

### CRITICAL

---

#### [C1] Blocking Kafka Publish Holds All Consumer Threads

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/consumer/DiscoveryEventConsumer.java:197`

```java
// CURRENT (violates CLAUDE.md rule)
kafkaTemplate.send(record).get(30, TimeUnit.SECONDS);
```

With `concurrency: 3`, a single Kafka broker network hiccup pins all 3 listener threads for up to 30 seconds simultaneously, suspending forward progress on all assigned partitions. This violates the project's own hard rule: **"Kafka publish: `kafkaTemplate.send().whenComplete(...)` — never `.get()`"**.

**Fix:** Replace with a `whenComplete` callback. If correctness requires not acking the inbound offset on publish failure, reduce the timeout to 2–3s and delegate to `DefaultErrorHandler` backoff rather than spinning in a 30s `.get()`.

---

#### [C2] Two Serial Blocking HTTP Calls on Semantic Path — Executor Starvation

**Files:**
- `jobs/catalog-discover-job/src/main/java/org/beckn/discover/service/elasticsearch/QueryEnricher.java:97`
- `jobs/catalog-discover-job/src/main/java/org/beckn/discover/service/elasticsearch/EmbeddingClient.java:110`

Both use `java.net.http.HttpClient.send()` (synchronous blocking) and run **sequentially**. Worst-case combined latency before the ES knn query even starts:

```
LLM timeout (30s) + embedding timeout (10s × 3 retries with 1s+2s backoff) = up to 44s per request
```

The `discoveryQueryExecutor` has 4 threads. Four concurrent semantic requests occupy all 4 threads indefinitely. At P99 LLM latency of 5–10s:

```
4 threads ÷ 10s = 0.4 req/s  →  all non-semantic paths also queue behind them
```

**Fix:** Switch both calls to `HttpClient.sendAsync()` and compose `CompletableFuture` chains. The `DiscoveryService` already wraps calls in `runAsyncWithMdc()` — async internal calls would return the thread to the pool during I/O wait, allowing the pool to serve other requests.

---

#### [C3] `POSTGRES_LOG_EXPLAIN_ANALYZE` Defaults to `true` in Production

**File:** `jobs/catalog-discover-job/src/main/resources/application.yml:147`

```yaml
log-explain-analyze: ${POSTGRES_LOG_EXPLAIN_ANALYZE:true}  # BUG: default should be false
```

`EXPLAIN (ANALYZE, BUFFERS, VERBOSE)` executes the query **for real** in addition to planning it, then returns a verbose plan that is string-joined and logged at INFO. Effect: every PostgreSQL query on Paths A, B, and C currently runs twice in production. At 50 req/s on Path B: +500 DB round-trips/s. Also floods structured log output with multi-kilobyte query plans.

**Fix (zero code change):**
```yaml
log-explain-analyze: ${POSTGRES_LOG_EXPLAIN_ANALYZE:false}
```

---

#### [C4] HTTP 429 / Back-Pressure Not Retried in BulkIndexService

**File:** `jobs/catalog-publish-job/src/main/java/org/beckn/catalogpublish/indexing/bulk/BulkIndexService.java`

`@Retryable` catches only `ConnectException` and `SocketTimeoutException`. When ES returns HTTP 429 (thread pool full or circuit breaker), the client throws `ElasticsearchException` (not a network exception), which falls through to the non-retryable catch block and routes documents to the dead-letter topic. This means ES back-pressure silently causes **permanent data loss from the indexing pipeline**.

**Fix:** Intercept `ElasticsearchException` with `e.status() == 429` and re-throw a retryable wrapper. Add it to `@Retryable(retryFor = {...})`.

---

### HIGH

---

#### [H1] Full `_source` Returned on Every ES Query — 50–80% Wasted Payload

**Files:** `ElasticsearchTextSearchEngine.java`, `ElasticsearchQueryEngine.java`

No `_source` includes/excludes are set on any search request. Every hit returns `full_text_blob` (potentially several KB of concatenated text) and `resource_vector` (1536 floats = ~6KB encoded). `EsSearchAssembler` never reads either field. At `size: 50` hits per query, this is up to **350KB of discarded payload per request**.

**Fix:** Add source filtering to all search requests:
```java
.sourceFilter(sf -> sf.excludes("full_text_blob", "resource_vector", "indexed_at"))
```

---

#### [H2] `ProviderOfferEnricher` Unconditional DB Round-Trip on Every Response

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/service/DiscoveryService.java:440`

`providerOfferEnricher.enrich(processed)` executes a `SELECT ... FROM provider_offer WHERE provider_id IN (...)` on every non-empty response, across all query paths. No caching. At 100 req/s: 100 additional DB queries/s consuming Hikari pool connections.

**Fix:** Cache provider offer data with Caffeine (TTL 30–60s). Provider-level offers change only on catalog publish events. A 60s cache eliminates >99% of these queries under any sustained load.

---

#### [H3] `EmbeddingClient` — 33-Second Thread Hold on Failed Embedding

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/service/elasticsearch/EmbeddingClient.java:99`

Single failed embedding attempt: `10s timeout + 1s delay + 10s timeout + 2s delay + 10s timeout = 33 seconds` of thread hold time. Identified again here because this compounds with C2 — the fix for C2 (async HTTP) also resolves this.

---

#### [H4] Geo-Shape Queries in `must` Instead of `filter` — Both Spatial-Only AND Text+Spatial Paths

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/service/elasticsearch/ElasticsearchQueryEngine.java:148–183`

Geo-shape queries are placed in `bool.must` on **both sub-paths**:

```java
List<Query> mustQueries = new ArrayList<>(geoQueries);   // ← geo in must, always
...
if (hasText) {
    mustQueries.add(Query.of(q -> q.multiMatch(...)));    // text also in must
}
finalQueries.forEach(bq::must);     // geo + text both in must
finalSchemaFilters.forEach(bq::filter);
```

- **`hasText=false` (spatial only):** geo-shape produces constant scores; `must` forces trivial score computation and prevents node query cache from caching result bitsets.
- **`hasText=true` (BM25 + spatial):** geo-shape is still in `must` alongside `multi_match`. Even with text scoring present, geo clauses have no scoring contribution and should be in `filter` to benefit from caching on repeated similar queries.

**Fix:** Geo-shape queries should always be in `bool.filter`. Only `multi_match` belongs in `must`:
```java
geoQueries.forEach(bq::filter);        // always filter
if (hasText) bq.must(multiMatchQuery); // only text in must
finalSchemaFilters.forEach(bq::filter);
```

---

#### [H5] `ensureIndex()` Issues 2–4 HTTP Round-Trips Per Bulk Batch

**File:** `jobs/catalog-publish-job/src/main/java/org/beckn/catalogpublish/indexing/EsIndexManager.java`

`ensureIndex()` calls `indices().exists()` + optional `indices().create()` + `indices().existsAlias()` + optional `indices().putAlias()` before every bulk operation. After first creation, this is pure overhead.

**Fix:** Cache the set of known indexes in a `ConcurrentHashSet`. Skip the HTTP call if the index was previously confirmed to exist. Invalidate on startup only.

---

#### [H6] `EmbeddingClient` — Serial Embedding Per Item During Indexing

**File:** `jobs/catalog-publish-job/src/main/java/org/beckn/catalogpublish/indexing/ElasticIndexStep.java`

Embedding is computed synchronously per item inside the indexing loop:
```java
client.embed(itemJson).ifPresent(vec -> doc.put("resource_vector", vec));
```

For a catalog with 100 items at 100ms per embedding = **10 seconds of serial embedding latency per catalog publish event**, blocking the `esIndexExecutor` thread.

**Fix:** Batch embed via parallel `CompletableFuture.allOf()` across items in a schema-type group before bulk indexing.

---

---

## ES Text Search Gaps

_Added 2026-05-04 from deep-dive code review of the ES text search path._

---

#### [C5] GET Handler — Paths B and C Block Tomcat Thread Indefinitely with No Timeout

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/controller/DiscoveryController.java:140` → `DiscoveryService.java`

The `GET /beckn/discover` handler calls `discoveryService.processDiscoveryRequest(request)` synchronously on the Tomcat HTTP thread. Inside `route()`:

- **Path D (text search):** wrapped in `runAsyncWithMdc().get(timeoutSec)` — correctly bounded.
- **Path A-parallel:** wrapped in `CompletableFuture.allOf().get(timeoutSec)` — correctly bounded.
- **Path B (`executeFilterQuery`):** called **directly on the Tomcat thread with no timeout**. One slow or locked PostgreSQL query holds the thread indefinitely.
- **Path C (`executeSpatialQuery`):** same — **direct blocking call, no timeout**.

At 200ms average Path B query time, Tomcat's default 200 thread pool limits GET throughput to 1000 req/s. A single runaway DB query (lock contention, missing GIN index) holds a Tomcat thread for the full query duration — 30 slow concurrent queries fill all 200 threads, dropping throughput to zero.

**Fix:** Wrap all engine calls in `executeTextSearchQuery`'s pattern — submit to `queryExecutor` and `get(timeoutSec, SECONDS)`. This gives Paths B and C the same timeout protection as Path D.

---

#### [H7] `buildTextSearchJson()` Serialized on Every Request at INFO Level

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/service/elasticsearch/ElasticsearchTextSearchEngine.java:173–176`

```java
log.info(LogEvent.ES_SEARCH_STARTED + ".keyword",
        value("query", buildTextSearchJson(ErrorSanitizer.sanitize(text))));  // ← always runs
```

`buildTextSearchJson()` (lines 270–286) allocates a `LinkedHashMap`, builds the query body map, then calls `objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body)` — full pretty-print Jackson serialization — **on every keyword search request at INFO level**, without a `log.isInfoEnabled()` guard or lazy supplier. At 100 req/s this is 100 unnecessary Jackson serializations per second.

**Fix:** Move to DEBUG level and use a lazy supplier:
```java
log.debug(LogEvent.ES_SEARCH_STARTED + ".keyword",
        value("query", (Supplier<String>) () -> buildTextSearchJson(ErrorSanitizer.sanitize(text))));
```

---

#### [H8] `fuzziness("AUTO")` Applied to `full_text_blob` via Multi-Match

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/service/elasticsearch/ElasticsearchTextSearchEngine.java:181–185`

```java
b.must(Query.of(mq -> mq.multiMatch(mm -> mm
        .query(text)
        .fields(multiMatchFields)   // includes "full_text_blob"
        .type(TextQueryType.BestFields)
        .fuzziness("AUTO"))));      // ← applied across ALL fields
```

`fuzziness("AUTO")` forces ES to generate Levenshtein edit-distance variants (up to edit distance 2 for terms > 5 chars) for each query token and match them against the inverted index of `full_text_blob` — a large field with many unique tokens. This expands candidate sets significantly. `full_text_blob` is a concatenated blob that already benefits from standard BM25 matching; fuzziness adds CPU cost with minimal relevance gain on that field.

**Fix:** Apply `fuzziness` only to short exact-match fields. Use a separate `match` with no fuzziness for `full_text_blob`:
```java
// Scored text: exact BM25 on blob, fuzzy only on name
b.must(Query.of(mq -> mq.match(m -> m.field("full_text_blob").query(text))))
 .must(Query.of(mq -> mq.match(m -> m.field("resource_name").query(text).fuzziness("AUTO").boost(2f))))
```

---

#### [H9] `GET /beckn/discover` Uses `@RequestBody` — RFC Violation and Proxy Stripping Risk

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/controller/DiscoveryController.java:88–94`

```java
@GetMapping("/discover")
public ResponseEntity<DiscoverResponse> discover(
        @RequestBody byte[] rawBytes, ...
```

RFC 7231 §4.3.1: "A payload within a GET request message has no defined semantics." Many HTTP intermediaries (nginx, AWS ALB, Cloudflare, corporate proxies) strip the body from GET requests silently. Clients behind such proxies will receive schema validation NACKs with valid payloads and no observable network error. GET is also semantically wrong for a Beckn discover action — it is not idempotent (triggers a search), not cacheable (carries a full JSON request payload), and the Beckn protocol defines `discover` as a POST operation.

**Fix:** The GET endpoint should become `POST /beckn/discover/sync` (or similar) to clearly distinguish the synchronous response variant from the async POST. Update the route annotation and any Postman collections.

---

#### [H10] Tomcat Thread Pool Is Entirely Commented Out — Using Spring Boot Defaults

**File:** `jobs/catalog-discover-job/src/main/resources/application.yml:218–228`

```yaml
# server:
#   tomcat:
#     threads:
#       max: ${SERVER_TOMCAT_MAX_THREADS:50}
#     accept-count: ${SERVER_TOMCAT_ACCEPT_COUNT:100}
#     max-connections: ${SERVER_TOMCAT_MAX_CONNECTIONS:2000}
```

The entire Tomcat thread pool configuration is commented out. Spring Boot defaults to **200 max threads**. The commented-out value of 50 (≈ 5× vCPU) is the correct production target for a CPU-bound service. At 200 threads on a 1-vCPU container, context-switching overhead dominates CPU time at moderate-to-high request rates, and thread stack memory alone is 200MB (1MB per thread stack).

**Fix:** Uncomment and tune:
```yaml
server:
  tomcat:
    threads:
      max: ${SERVER_TOMCAT_MAX_THREADS:50}
      min-spare: ${SERVER_TOMCAT_MIN_SPARE_THREADS:10}
    accept-count: ${SERVER_TOMCAT_ACCEPT_COUNT:100}
    max-connections: ${SERVER_TOMCAT_MAX_CONNECTIONS:2000}
```

---

### MEDIUM

---

#### [M1] ES Connection Pool Not Tuned for Concurrency

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/config/EsSearchConfig.java`

The `RestClient.builder()` uses default Apache HttpAsyncClient pool settings (10 connections per route, 30 total). With `Kafka concurrency: 3` + `parallelQueryWorkers: 4`, there are 7 potential concurrent ES requests. Default pool may cause queuing under burst.

**Fix:**
```java
.setHttpClientConfigCallback(cfg -> cfg
    .setMaxConnPerRoute(20)
    .setMaxConnTotal(40))
```

---

#### [M2] No `trackTotalHits(false)` on Search Queries

**Files:** `ElasticsearchTextSearchEngine.java`, `ElasticsearchQueryEngine.java`

ES defaults to counting accurate total hits across all matching documents — even when only top-N are returned and the application never uses the total count. For queries matching large fractions of the index, this adds measurable shard-level latency.

**Fix:** Add `.trackTotalHits(t -> t.enabled(false))` to all search requests.

---

#### [M3] `offers` Nested Type Has No Explicit Sub-Field Mappings

**File:** `config/es-index-template.json`

`offers` is mapped as `nested` with `"dynamic": true`. Variable offer schemas across publishers will trigger dynamic mapping additions on every new field name, eventually hitting the `total_fields.limit: 2000`.

**Fix:** Add explicit sub-field mappings for known offer fields (`id`, `descriptor.name`, `resourceIds`, `validity.startDate`, `validity.endDate`). Set `"dynamic": false` on the `offers` nested object.

---

#### [M4] Unbounded `full_text_blob` in Document Assembly

**File:** `jobs/catalog-publish-job/src/main/java/org/beckn/catalogpublish/indexing/document/CatalogDocumentAssembler.java`

`buildTextBlob()` concatenates names, descriptions, attributes, constraints, policies, docs, media metadata, and all offer text with no length limit. Large catalogs can produce multi-KB blobs that inflate index size, slow BM25 scoring, and increase memory pressure during search.

**Fix:** Cap `full_text_blob` at a configurable maximum (e.g., 8KB). Truncate with a clean word boundary.

---

#### [M5] ES Search Latency Histograms Not Configured for p95/p99

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/service/DiscoveryMetrics.java`

`discovr.discover.search.duration` Timer lacks explicit histogram bucket configuration. Default Micrometer histogram buckets may not align with ES SLA thresholds (10ms, 50ms, 100ms, 200ms, 500ms, 1s, 5s, 30s).

**Fix:**
```java
Timer.builder("discovr.discover.search.duration")
    .serviceLevelObjectives(
        Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(100),
        Duration.ofMillis(200), Duration.ofMillis(500), Duration.ofSeconds(1),
        Duration.ofSeconds(5), Duration.ofSeconds(30))
    .register(registry);
```

---

#### [M7] `EsSchemaFilterBuilder` Wraps Single-Pair Filter in Unnecessary `bool.should`

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/service/elasticsearch/EsSchemaFilterBuilder.java:152–157`

```java
Query schemaFilter = Query.of(q -> q.bool(b -> {
    pairs.forEach(b::should);
    b.minimumShouldMatch("1");
    return b;
}));
```

When there is exactly **one schema URL** (the common case — most Beckn requests target a single resource type), this produces:
```
filter: bool.should[ bool.must[term(context), term(type)] ] minimumShouldMatch=1
```
The outer `bool.should` wrapping a single `must` pair adds an unnecessary query node. ES evaluates an extra bool layer per shard segment, and the query structure is also used as the node query cache key — nested structures reduce cache hit rates. For the single-pair case, the filter should be two direct `filter` terms.

**Fix:** Short-circuit when `pairs.size() == 1`: return the single pair directly without the `bool.should` wrapper.

---

#### [M8] KNN `numCandidates=500` Unconditional — Not Adaptive by Filter Selectivity

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/service/elasticsearch/ElasticsearchTextSearchEngine.java:80`, `application.yml:100`

```java
this.knnCandidates = Math.max(props.getTextSearch().getEmbeddingModel().getKnnCandidates(), resultLimit);
// default: 500
```

`numCandidates=500` is applied unconditionally regardless of schema context filters. When narrow schema filters are present (e.g., `GroceryItem` matching a few hundred documents), ES applies the pre-filter first and then searches `numCandidates` candidates within that filtered subset. For a filtered index of 200 documents, 500 candidates is more than the entire corpus — all documents are visited. For broad unfiltered searches, 500 may provide insufficient recall. `numCandidates` should scale with the expected candidate space.

**Fix:** Make `numCandidates` configurable per-query based on schema filter presence, or expose `numCandidates` as a config property with clear guidance on tuning (currently the default of 500 is never reassessed).

---

#### [M9] Signature Verification Runs on Tomcat Thread Per Request — No Per-Request Result Caching

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/controller/DiscoveryController.java:134`

```java
authorizationService.authorizeRequest(rawBody, headers);
```

When `SIGNATURE_AUTH_ENABLED=true`, this performs: (1) parse `Authorization: Signature` header, (2) look up the subscriber's public key from the Beckn registry — the key lookup IS cached. However, (3) Ed25519 signature verification over the full raw request body runs **on the Tomcat thread on every request with no caching**. At 100 req/s from a single BAP, 100 crypto verifications run per second on Tomcat threads, each operating on the full raw body bytes.

**Fix:** This is inherent to the Beckn auth design (signatures include timestamp + request body digest, so the same BAP making 100 requests produces 100 distinct signatures). The mitigation is off-thread execution — run `authorizeRequest` in `queryExecutor` so Tomcat threads are freed during the crypto operation. Alternatively, move to a dedicated `authExecutor` pool sized for crypto throughput.

---

#### [M10] No `messageId` Idempotency Check Before Kafka Publish on POST Path

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/controller/DiscoveryController.java:190`

```java
var record = new ProducerRecord<>(requestTopic, null, kafkaKey, rawBody, kafkaHeaders);
kafkaTemplate.send(record).whenComplete(...)
```

If a BAP retries `POST /beckn/discover` (e.g., network timeout after the server returned ACK), the same `messageId` is published to Kafka again and processed by `DiscoveryEventConsumer` a second time, triggering a duplicate `on_discover` callback to the BAP. No deduplication on `messageId` before publishing.

**Fix:** Add a short-lived Caffeine cache (TTL = `parallelQueryTimeoutSeconds` + callback roundtrip, e.g., 60s) keyed on `messageId`. If the key is already present, return `ACK` immediately without re-publishing to Kafka.

---

#### [M6] No `refresh_interval` in Index Template — 1s Default Adds Indexing Overhead

**File:** `config/es-index-template.json`

The default 1-second segment refresh adds overhead during heavy catalog publish operations. During bulk indexing, `refresh_interval: -1` (disabled) significantly improves throughput; re-enable post-bulk.

**Fix:** Set `"refresh_interval": "5s"` in the index template settings as a baseline. For bulk import operations, set to `-1` during the import and then call `/_refresh` explicitly.

---

### LOW

---

#### [L1] `resource_attributes` Has `"dynamic": true` — Mapping Explosion Risk

**File:** `config/es-index-template.json`

Any arbitrary field inside `resourceAttributes` sent by publishers will trigger a dynamic mapping add. With many publishers sending heterogeneous attribute schemas, this can rapidly approach the `total_fields.limit: 2000`.

**Fix:** Set `"dynamic": false` on `resource_attributes`. Known attribute fields (`@context`, `@type`) are already explicitly mapped. Unknown fields from `flattenJsonLd` can be stored under a dedicated `resource_attributes.extra` `object` with `"enabled": false` (stored but not indexed).

---

#### [L2] No `number_of_shards` / `number_of_replicas` in Index Template

**File:** `config/es-index-template.json`

New per-schema-type indexes use cluster defaults, which vary by cluster configuration. Target: 1 shard per index for small catalogs (< 5GB), 3–5 shards for large catalogs. Make this explicit.

---

#### [L3] No ES Cluster Health Exposed via Application

Neither job exposes ES cluster health (thread pool rejections, circuit breaker status, JVM heap, GC pressure) through the application's Actuator/health endpoint. ES degradation is invisible until queries fail.

**Fix:** Add an `ElasticsearchHealthIndicator` bean to both jobs that pings `/_cluster/health` and maps `green`→UP, `yellow`→UNKNOWN, `red`/timeout→DOWN.

---

#### [L4] No ES Authentication Configuration

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/config/EsSearchConfig.java`

The `RestClient` has no authentication setup. Production ES clusters should use API key or basic auth.

**Fix:** Add optional `${ES_API_KEY}` or `${ES_USERNAME}` / `${ES_PASSWORD}` configuration to `DiscoveryProperties` and wire into `RestClient` `setDefaultHeaders`.

---

#### [L6] `assembleAndLog()` Materializes Intermediate `List<Map>` Before Assembler

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/service/elasticsearch/ElasticsearchTextSearchEngine.java:223–228`

```java
List<Map<String, Object>> hits = rawHits.stream()
        .map(Hit::source)
        .filter(Objects::nonNull)
        .map(m -> (Map<String, Object>) m)
        .toList();                          // ← intermediate list
List<Catalog> catalogs = assembler.assemble(hits, txId);
```

At `size: 50` this allocates a 50-element intermediate `List<Map>` solely to pass to the assembler which iterates it linearly once. Minor allocation on the hot path.

**Fix:** Pass the stream directly if `EsSearchAssembler.assemble()` is refactored to accept an `Iterable<Map>`, or use `Stream.toList()` which already returns an unmodifiable list — no change needed unless the assembler signature changes.

---

#### [L7] Three-Phase JSON Deserialization on Every Request (bytes → String → tree → POJO)

**File:** `jobs/catalog-discover-job/src/main/java/org/beckn/discover/controller/DiscoveryController.java:117–139`

```java
String rawBody = new String(rawBytes, StandardCharsets.UTF_8);  // phase 1: bytes → String
JsonNode requestNode = objectMapper.readTree(rawBody);           // phase 2: String → tree
DiscoverRequest request = objectMapper.convertValue(requestNode, DiscoverRequest.class); // phase 3: tree → POJO
```

The `String` allocation is required for Beckn signature verification (signs over the raw string). The tree is required for schema validation. `convertValue(tree, POJO)` internally re-serializes the tree to bytes and re-parses — a 4th pass. `objectMapper.treeToValue(requestNode, DiscoverRequest.class)` avoids re-serialization but is otherwise equivalent. Also: `objectMapper.readTree(rawBytes)` could replace phase 1+2, but auth needs the String — so this is partially constrained.

**Fix:** Replace `convertValue` with `treeToValue` to eliminate the internal re-serialization cycle. Consider passing `rawBytes` directly to `objectMapper.readTree(rawBytes)` and retaining `rawBody` only for auth.

---

#### [L5] Application-Level Query Cache Not Implemented

For the keyword path, identical `discover` queries (same text + same schema context) within a short window will re-execute the same ES query. A 30s Caffeine cache keyed on `(queryText + schemaContextHash)` could absorb significant repeated load from BAP retry storms.

---

## Prioritized Action Plan

_(Updated 2026-05-04: 11 new findings added from ES text search + GET API deep-dive)_

| Priority | ID | File | Change | Effort |
|----------|----|------|--------|--------|
| **CRITICAL** | C1 | `DiscoveryEventConsumer.java:197` | Replace `.get(30s)` with `.whenComplete(...)` | 1h |
| **CRITICAL** | C2 | `QueryEnricher.java:97`, `EmbeddingClient.java:110` | Switch to `HttpClient.sendAsync()` | 2h |
| **CRITICAL** | C3 | `application.yml:147` | Change default to `false` | 5min |
| **CRITICAL** | C4 | `BulkIndexService.java` | Catch 429 and re-throw retryable | 1h |
| **CRITICAL** | C5 | `DiscoveryController.java:140` → `DiscoveryService` Paths B+C | Wrap filter/spatial queries in `queryExecutor` with timeout on GET handler | 2h |
| **HIGH** | H1 | `ElasticsearchTextSearchEngine.java`, `ElasticsearchQueryEngine.java` | Add `_source` excludes (`full_text_blob`, `resource_vector`, `indexed_at`) | 30min |
| **HIGH** | H2 | `DiscoveryService.java:440` | Cache `provider_offer` with Caffeine 60s TTL | 2h |
| **HIGH** | H3 | _(resolved by C2)_ | — | — |
| **HIGH** | H4 | `ElasticsearchQueryEngine.java` | Move geo-shape to `filter` on both `hasText=true` and `hasText=false` paths | 30min |
| **HIGH** | H5 | `EsIndexManager.java` | Cache known index names in `ConcurrentHashSet` | 1h |
| **HIGH** | H6 | `ElasticIndexStep.java` | Batch embed with `CompletableFuture.allOf()` | 2h |
| **HIGH** | H7 | `ElasticsearchTextSearchEngine.java:173` | Move `buildTextSearchJson()` to DEBUG + lazy supplier | 15min |
| **HIGH** | H8 | `ElasticsearchTextSearchEngine.java:183` | Remove `fuzziness` from `full_text_blob`; apply only to `resource_name` | 30min |
| **HIGH** | H9 | `DiscoveryController.java:88` | Replace `@GetMapping` with `@PostMapping("/discover/sync")` or rename | 2h |
| **HIGH** | H10 | `application.yml:218` | Uncomment Tomcat thread pool; set `max: 50`, `min-spare: 10` | 15min |
| **MEDIUM** | M1 | `EsSearchConfig.java` | Set max connection pool to 20/40 | 15min |
| **MEDIUM** | M2 | All search methods | Add `trackTotalHits(false)` | 15min |
| **MEDIUM** | M3 | `es-index-template.json` | Explicit `offers` sub-mappings; set `dynamic: false` | 1h |
| **MEDIUM** | M4 | `CatalogDocumentAssembler.java` | Cap `full_text_blob` at 8KB | 30min |
| **MEDIUM** | M5 | `DiscoveryMetrics.java` | Configure SLO histogram buckets | 30min |
| **MEDIUM** | M6 | `es-index-template.json` | Set `refresh_interval: 5s` | 15min |
| **MEDIUM** | M7 | `EsSchemaFilterBuilder.java:152` | Short-circuit `bool.should` wrapper for single-pair filter | 1h |
| **MEDIUM** | M8 | `ElasticsearchTextSearchEngine.java:80` | Document `knnCandidates` tuning guidance; expose adaptive sizing | 1h |
| **MEDIUM** | M9 | `DiscoveryController.java:134` | Move signature verification off Tomcat thread to `queryExecutor` | 1h |
| **MEDIUM** | M10 | `DiscoveryController.java:190` | Add Caffeine `messageId` deduplication cache (TTL 60s) before Kafka publish | 1h |
| **LOW** | L1 | `es-index-template.json` | Set `dynamic: false` on `resource_attributes` | 1h |
| **LOW** | L2 | `es-index-template.json` | Explicit `number_of_shards` / `number_of_replicas` | 30min |
| **LOW** | L3 | Both jobs | Add `ElasticsearchHealthIndicator` | 1h |
| **LOW** | L4 | `EsSearchConfig.java` | Add optional ES auth via `${ES_API_KEY}` | 1h |
| **LOW** | L5 | `ElasticsearchTextSearchEngine.java` | Caffeine query result cache (TTL 30s) keyed on text+schemaHash | 2h |
| **LOW** | L6 | `ElasticsearchTextSearchEngine.java:223` | Minor: eliminate intermediate `List<Map>` if assembler accepts `Iterable` | 30min |
| **LOW** | L7 | `DiscoveryController.java:117` | Replace `convertValue` with `treeToValue`; feed `rawBytes` to `readTree` | 30min |

---

## What the Implementation Gets Right

- Modern `co.elastic.clients.elasticsearch.ElasticsearchClient` (not deprecated HLRC)
- Correct `bool.must` for scored text query + `bool.filter` for schema context filters on keyword (`native-els`) path
- KNN schema filters pushed into `knn.filter` (not post-filter) — correct pre-filtering before ANN
- Singleton `RestClient` bean — connection pool correctly shared
- Custom analyzers with search-time synonym expansion (recommended pattern)
- Dual relevance filtering: absolute `minScore` + relative threshold against top hit
- Bulk indexing with `@Retryable` exponential backoff + Kafka failure recovery pipeline
- Dedicated `esIndexExecutor` thread pool decoupled from Kafka consumer threads
- Deterministic ES document ID (`catalogId:resourceId`) — idempotent upserts
- Good Micrometer coverage on both publish and discover sides
- `DefaultErrorHandler` on Kafka consumer (correct — no ack on transient failures)
- POST `/beckn/discover` correctly uses `whenComplete` for Kafka publish (fire-and-forget ACK path)
- MDC propagated into async executor threads via `runAsyncWithMdc()` snapshot pattern

---

## Reference Reports

- Full profiling detail: `docs/perf-review-catalog-discover-job-2026-04-30-1122.md`
- Full ES research + code analysis: `docs/elasticsearch-highthroughput-research.md`
