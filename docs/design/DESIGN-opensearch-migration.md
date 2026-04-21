# Design: Migrate from Elasticsearch to OpenSearch

**Status:** PROPOSED
**Date:** 2026-04-20

---

## 1. Problem Statement

Beckn Discovr currently depends on Elasticsearch 9.3.1 which is distributed under the Elastic License 2.0 (ELv2). ELv2 is **not** an open-source license: it prohibits offering Elasticsearch as a managed service and restricts certain redistribution scenarios. This creates legal and operational risk for any Beckn network participant that wants to deploy Discovr without Elastic commercial terms.

OpenSearch is an Apache 2.0 licensed fork of Elasticsearch maintained by AWS and a broad open-source community. It provides API-level compatibility with Elasticsearch 7.x/8.x for all features Discovr uses: bulk indexing, BM25 text search, KNN vector search, geo_shape spatial queries, custom analyzers with synonyms, and index templates.

**Goal:** Replace Elasticsearch with OpenSearch across all Discovr components with zero functionality loss, minimal code changes, and a clean re-index from PostgreSQL (the source of truth).

---

## 2. Current Architecture

### 2.1 Components That Use Elasticsearch

| Component | Role | Client Library |
|-----------|------|----------------|
| **catalog-publish-job** | Index-time: bulk indexing, index template management, delete-by-query (FULL mode), ES failure retry consumer | `co.elastic.clients:elasticsearch-java:8.15.0` |
| **catalog-discover-job** | Query-time: BM25 text search, KNN vector search, geo_shape spatial queries, schema context filtering | `co.elastic.clients:elasticsearch-java:8.15.0` + `org.elasticsearch.client:elasticsearch-rest-client:8.15.0` |

### 2.2 Feature Inventory

| Feature | Current ES Implementation | Files |
|---------|--------------------------|-------|
| **Bulk indexing** | `BulkRequest` / `BulkResponse` via `ElasticsearchClient` | `BulkIndexService.java` |
| **Index template** | `putIndexTemplate` with dynamic templates, custom analyzers | `EsIndexManager.java`, `es-index-template.json` |
| **Delete-by-query** | `deleteByQuery` on `catalog_id` term | `BulkIndexService.deleteByCatalog()` |
| **BM25 text search** | `multi_match` with `BestFields`, `fuzziness: AUTO` | `ElasticsearchTextSearchEngine.java` |
| **KNN vector search** | Top-level `knn` clause with `resource_vector` field | `ElasticsearchTextSearchEngine.java`, `ElasticsearchQueryEngine.java` |
| **Custom analyzers** | `beckn_text` (index), `beckn_text_search` (search, with synonyms) | `es-index-template.json`, `EsIndexManager.defaultTemplateJson()` |
| **Synonym file** | External `config/es-synonyms.txt` mounted into ES | `config/es-synonyms.txt` |
| **geo_shape queries** | `GeoShapeRelation.Intersects/Within/Contains/Disjoint` | `EsSpatialQueryBuilder.java` |
| **Schema context filter** | `term` queries on `resource_attributes_context` / `resource_attributes_type` | `EsSchemaFilterBuilder.java` |
| **dense_vector** | 1536 dims, cosine similarity, indexed | `es-index-template.json`, `EsIndexManager.defaultTemplateJson()` |
| **Embedding generation** | OpenAI-compatible `/v1/embeddings` API | `EmbeddingClient.java` (both jobs) |
| **LLM query enrichment** | OpenAI-compatible `/v1/chat/completions` API | `QueryEnricher.java` |
| **ES failure retry** | Kafka-based retry with `EsFailureConsumer` | `EsFailureConsumer.java`, `EsFailurePublisher.java` |
| **min_score** | Applied on search requests | `ElasticsearchTextSearchEngine.java` |
| **Relative score threshold** | Post-query client-side filtering | `ElasticsearchTextSearchEngine.filterByRelativeScore()` |

### 2.3 Docker

```yaml
# Current: docker-compose.yml
elasticsearch:
  image: docker.elastic.co/elasticsearch/elasticsearch:9.3.1
  environment:
    - discovery.type=single-node
    - xpack.security.enabled=false
    - ES_JAVA_OPTS=-Xms512m -Xmx512m
```

### 2.4 Testcontainers

```java
// catalog-discover-job: ElasticsearchTextSearchEngineIntegrationTest.java
DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.0")
ElasticsearchContainer ES_CONTAINER = new ElasticsearchContainer(ES_IMAGE)
    .withEnv("xpack.security.enabled", "false")
    .withEnv("discovery.type", "single-node")
```

---

## 3. Proposed Changes

### 3.1 Dependency Changes

#### catalog-publish-job/build.gradle

```diff
- implementation 'co.elastic.clients:elasticsearch-java:8.15.0'
+ implementation 'org.opensearch.client:opensearch-java:2.18.0'
```

The Elastic `elasticsearch-java` client transitively pulls in `org.elasticsearch.client:elasticsearch-rest-client`. When switching to OpenSearch, we need the OpenSearch REST client instead. The `opensearch-java` client includes its own transport layer.

#### catalog-discover-job/build.gradle

```diff
- // Elasticsearch Java client (text search engine)
- implementation 'co.elastic.clients:elasticsearch-java:8.15.0'
- implementation 'org.elasticsearch.client:elasticsearch-rest-client:8.15.0'
+ // OpenSearch Java client (text search engine)
+ implementation 'org.opensearch.client:opensearch-java:2.18.0'

  // Test dependencies
- testImplementation 'org.testcontainers:elasticsearch'
+ testImplementation 'org.testcontainers:elasticsearch'  // still supports OpenSearch images
```

**Version rationale:** `opensearch-java:2.18.0` is the latest stable release compatible with OpenSearch 2.19.x. The 2.x client line supports all features used by Discovr.

### 3.2 Docker Image Change

#### docker-compose.yml

```diff
  elasticsearch:
-   image: docker.elastic.co/elasticsearch/elasticsearch:9.3.1
+   image: opensearchproject/opensearch:2.19.1
    container_name: discovery-elasticsearch
    ports:
      - "9200:9200"
    environment:
-     - discovery.type=single-node
-     - xpack.security.enabled=false
-     - ES_JAVA_OPTS=-Xms512m -Xmx512m
+     - discovery.type=single-node
+     - DISABLE_SECURITY_PLUGIN=true
+     - OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m
+     - plugins.security.disabled=true
+     - DISABLE_INSTALL_DEMO_CONFIG=true
    volumes:
      - elasticsearch_data:/usr/share/elasticsearch/data
-     - ./config/es-synonyms.txt:/usr/share/elasticsearch/config/config/es-synonyms.txt
+     - ./config/es-synonyms.txt:/usr/share/opensearch/config/config/es-synonyms.txt
```

**Key differences:**
- `xpack.security.enabled=false` becomes `DISABLE_SECURITY_PLUGIN=true` + `plugins.security.disabled=true`
- `ES_JAVA_OPTS` becomes `OPENSEARCH_JAVA_OPTS`
- Config directory changes from `/usr/share/elasticsearch/config/` to `/usr/share/opensearch/config/`
- `DISABLE_INSTALL_DEMO_CONFIG=true` prevents the demo security config from being generated

### 3.3 Client Configuration Changes

The OpenSearch Java client has an almost identical API surface to the Elastic Java client. The primary change is import paths and the client class name.

#### EsIndexingConfig.java (catalog-publish-job)

```diff
- import co.elastic.clients.elasticsearch.ElasticsearchClient;
- import co.elastic.clients.json.jackson.JacksonJsonpMapper;
- import co.elastic.clients.transport.rest_client.RestClientTransport;
- import org.apache.http.HttpHost;
- import org.elasticsearch.client.RestClient;
+ import org.opensearch.client.opensearch.OpenSearchClient;
+ import org.opensearch.client.json.jackson.JacksonJsonpMapper;
+ import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
+ import org.apache.hc.core5.http.HttpHost;

  @Bean
- public RestClient esRestClient(AppProperties props) {
+ public OpenSearchClient openSearchClient(AppProperties props) {
      String hosts = props.catalog().elasticsearch().hosts();
      // ... host parsing ...
-     HttpHost[] httpHosts = Arrays.stream(hosts.split(","))
-             .map(String::trim)
-             .map(HttpHost::create)
-             .toArray(HttpHost[]::new);
-     return RestClient.builder(httpHosts)
-             .setRequestConfigCallback(cfg -> cfg.setConnectTimeout(5_000).setSocketTimeout(30_000))
-             .build();
- }
-
- @Bean
- public ElasticsearchClient elasticsearchClient(RestClient esRestClient) {
-     return new ElasticsearchClient(new RestClientTransport(esRestClient, new JacksonJsonpMapper()));
+     HttpHost[] httpHosts = Arrays.stream(hosts.split(","))
+             .map(String::trim)
+             .map(HttpHost::create)
+             .toArray(HttpHost[]::new);
+     var transport = ApacheHttpClient5TransportBuilder.builder(httpHosts)
+             .setMapper(new JacksonJsonpMapper())
+             .build();
+     return new OpenSearchClient(transport);
  }
```

#### EsSearchConfig.java (catalog-discover-job)

```diff
- import co.elastic.clients.elasticsearch.ElasticsearchClient;
- import co.elastic.clients.json.jackson.JacksonJsonpMapper;
- import co.elastic.clients.transport.rest_client.RestClientTransport;
- import org.apache.http.HttpHost;
- import org.elasticsearch.client.RestClient;
+ import org.opensearch.client.opensearch.OpenSearchClient;
+ import org.opensearch.client.json.jackson.JacksonJsonpMapper;
+ import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
+ import org.apache.hc.core5.http.HttpHost;

  @Bean
- public RestClient esRestClient(DiscoveryProperties props) {
-     // ...
-     return RestClient.builder(hosts)
-             .setRequestConfigCallback(...)
-             .build();
- }
-
- @Bean
- public ElasticsearchClient elasticsearchClient(RestClient esRestClient) {
-     return new ElasticsearchClient(new RestClientTransport(esRestClient, new JacksonJsonpMapper()));
+ public OpenSearchClient openSearchClient(DiscoveryProperties props) {
+     DiscoveryProperties.Elasticsearch es = props.getElasticsearch();
+     HttpHost[] hosts = Arrays.stream(es.getHosts().split(","))
+             .map(String::trim)
+             .map(HttpHost::create)
+             .toArray(HttpHost[]::new);
+     var transport = ApacheHttpClient5TransportBuilder.builder(hosts)
+             .setMapper(new JacksonJsonpMapper())
+             .build();
+     return new OpenSearchClient(transport);
  }
```

### 3.4 Import Rewriting (Mechanical)

Every Java file that imports `co.elastic.clients.*` or `org.elasticsearch.client.*` needs import rewriting. The API is structurally identical -- same method names, same builder patterns, same response types. The change is purely at the package level.

**Import mapping table:**

| Elasticsearch Import | OpenSearch Import |
|---------------------|-------------------|
| `co.elastic.clients.elasticsearch.ElasticsearchClient` | `org.opensearch.client.opensearch.OpenSearchClient` |
| `co.elastic.clients.elasticsearch._types.*` | `org.opensearch.client.opensearch._types.*` |
| `co.elastic.clients.elasticsearch._types.query_dsl.*` | `org.opensearch.client.opensearch._types.query_dsl.*` |
| `co.elastic.clients.elasticsearch._types.ElasticsearchException` | `org.opensearch.client.opensearch._types.OpenSearchException` |
| `co.elastic.clients.elasticsearch.core.*` | `org.opensearch.client.opensearch.core.*` |
| `co.elastic.clients.elasticsearch.core.bulk.*` | `org.opensearch.client.opensearch.core.bulk.*` |
| `co.elastic.clients.elasticsearch.core.search.*` | `org.opensearch.client.opensearch.core.search.*` |
| `co.elastic.clients.elasticsearch.indices.*` | `org.opensearch.client.opensearch.indices.*` |
| `co.elastic.clients.json.jackson.JacksonJsonpMapper` | `org.opensearch.client.json.jackson.JacksonJsonpMapper` |
| `co.elastic.clients.json.JsonData` | `org.opensearch.client.json.JsonData` |
| `co.elastic.clients.transport.rest_client.RestClientTransport` | `org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder` |
| `org.apache.http.HttpHost` | `org.apache.hc.core5.http.HttpHost` |
| `org.elasticsearch.client.RestClient` | *(removed -- transport is built directly)* |

**Files requiring import rewriting (11 total):**

catalog-publish-job (4 files):
1. `EsIndexingConfig.java` -- client bean creation
2. `EsIndexManager.java` -- `ElasticsearchClient` usage
3. `BulkIndexService.java` -- `ElasticsearchClient`, `BulkRequest`, `BulkResponse`, `BulkResponseItem`
4. `BulkIndexServiceTest.java` -- mock `ElasticsearchClient`

catalog-discover-job (7 files):
1. `EsSearchConfig.java` -- client bean creation
2. `ElasticsearchTextSearchEngine.java` -- `ElasticsearchClient`, `ElasticsearchException`, query DSL
3. `ElasticsearchQueryEngine.java` -- `ElasticsearchClient`, `ElasticsearchException`, query DSL
4. `EsSpatialQueryBuilder.java` -- `GeoShapeRelation`, `Query`, `JsonData`
5. `EsSchemaFilterBuilder.java` -- `Query`
6. `EsSchemaFilterBuilderTest.java` -- `BoolQuery`, `Query`, `TermQuery`
7. `ElasticsearchTextSearchEngineIntegrationTest.java` -- client, `BulkRequest`, `BulkResponse`, Testcontainers

### 3.5 Exception Type Rename

Two files catch `ElasticsearchException` by type to handle `index_not_found_exception`. This class is renamed in the OpenSearch client.

#### ElasticsearchTextSearchEngine.java and ElasticsearchQueryEngine.java

```diff
- import co.elastic.clients.elasticsearch._types.ElasticsearchException;
+ import org.opensearch.client.opensearch._types.OpenSearchException;

  // In catch blocks:
- } catch (ElasticsearchException e) {
+ } catch (OpenSearchException e) {
      if ("index_not_found_exception".equals(e.error().type())) {
```

The `e.error().type()` and `e.error().rootCause()` API is identical in the OpenSearch client. The error type string `"index_not_found_exception"` is also identical.

### 3.6 Vector Field: dense_vector to knn_vector

This is the most significant semantic change. Elasticsearch uses `dense_vector` while OpenSearch uses `knn_vector` with explicit method and engine configuration.

#### config/es-index-template.json

```diff
- "resource_vector": { "type": "dense_vector", "dims": 1536, "index": true, "similarity": "cosine" }
+ "resource_vector": {
+     "type": "knn_vector",
+     "dimension": 1536,
+     "method": {
+         "name": "hnsw",
+         "engine": "faiss",
+         "space_type": "cosinesimil",
+         "parameters": {
+             "ef_construction": 256,
+             "m": 16
+         }
+     }
+ }
```

Additionally, OpenSearch requires the `knn` plugin to be enabled at the index level:

```diff
  "template": {
    "settings": {
+     "index.knn": true,
      "index.mapping.total_fields.limit": 2000,
```

#### EsIndexManager.defaultTemplateJson() (built-in fallback template)

Apply the same changes as above in the Java text block.

**Field mapping differences:**

| Property | Elasticsearch | OpenSearch |
|----------|--------------|------------|
| Field type | `dense_vector` | `knn_vector` |
| Dimensions | `dims` | `dimension` |
| Index | `index: true` | Implied by `method` presence |
| Similarity | `similarity: "cosine"` | `space_type: "cosinesimil"` (in `method`) |
| ANN algorithm | Implicit HNSW | Explicit `method.name: "hnsw"` |
| Engine | N/A | `method.engine: "faiss"` (recommended for production) |
| HNSW params | Not configurable in mapping | `method.parameters: { ef_construction, m }` |

**Engine choice rationale:** FAISS is recommended for production OpenSearch deployments because it provides better recall/latency tradeoffs at scale than nmslib. `ef_construction: 256` and `m: 16` are production-grade defaults that balance index speed and search quality for 1536-dimensional embeddings.

### 3.7 KNN Query Syntax Change

Elasticsearch 8.x uses a top-level `knn` parameter on the search request. OpenSearch 2.x also supports top-level `knn` (since 2.17), so the query syntax is largely compatible. However, OpenSearch's `knn` parameter uses `min_score` differently.

#### ElasticsearchTextSearchEngine.java -- KNN search path

The current Elasticsearch code:
```java
esClient.search(s -> s
    .index(aliasName)
    .minScore(minScore)
    .size(resultLimit)
    .knn(k -> k.field("resource_vector")
        .queryVector(vec)
        .k(resultLimit)
        .numCandidates(knnCandidates)
        /* .filter(...) */
    ), Map.class);
```

OpenSearch 2.17+ supports the same top-level `knn` syntax. The `opensearch-java:2.18.0` client exposes `KnnQuery` via the same builder pattern. The `min_score` parameter is supported on the search request, and `filter` clauses inside `knn` are also supported.

**No structural query change is required.** The import rewriting (Section 3.4) handles the package rename. If the OpenSearch version deployed is older than 2.17, the alternative is to use `knn` inside a `bool.must` query, but since we target 2.19.1, the top-level syntax works.

#### ElasticsearchQueryEngine.java -- spatial + KNN path

Same situation: the OpenSearch client `knn` builder supports `.field()`, `.queryVector()`, `.k()`, `.numCandidates()`, and `.filter()` with identical semantics.

### 3.8 geo_shape Queries -- No Change

OpenSearch supports identical `geo_shape` query syntax with the same relation types: `intersects`, `within`, `contains`, `disjoint`. The `GeoShapeRelation` enum exists in the OpenSearch client at the same relative path. The `JsonData.of(shape)` approach for passing raw GeoJSON is identical.

The `circle` shape used by `s_dwithin` queries is also supported in OpenSearch.

**No structural change required** beyond import rewriting.

### 3.9 Custom Analyzers and Synonyms -- No Change

OpenSearch supports identical analyzer configuration:
- `custom` analyzer type with `standard` tokenizer
- `stop`, `stemmer`, and `synonym` token filters
- `synonyms_path` for external synonym files
- `search_analyzer` override on field mappings

The synonym file format is identical. The `config/es-synonyms.txt` file requires no changes.

**One path change:** The synonym file mount point in Docker changes from `/usr/share/elasticsearch/config/` to `/usr/share/opensearch/config/` (see Section 3.2).

### 3.10 Index Template API -- No Change

OpenSearch supports `_index_template` API with `index_patterns`, `template.settings`, `template.mappings`, and `dynamic_templates` using identical JSON structure. The `putIndexTemplate` method on the client is identical.

### 3.11 Bulk API -- No Change

`BulkRequest`, `BulkResponse`, `BulkResponseItem` have identical structure. The `bulk()` method call pattern is the same.

### 3.12 Delete-by-Query API -- No Change

`deleteByQuery` with a `term` query on `catalog_id` is identical in OpenSearch.

### 3.13 min_score -- No Change

OpenSearch supports `min_score` on search requests with identical semantics.

### 3.14 Testcontainers Update

#### ElasticsearchTextSearchEngineIntegrationTest.java

```diff
- private static final DockerImageName ES_IMAGE = DockerImageName
-         .parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.0")
-         .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch");
+ private static final DockerImageName OS_IMAGE = DockerImageName
+         .parse("opensearchproject/opensearch:2.19.1")
+         .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch");

  @Container
- static final ElasticsearchContainer ES_CONTAINER = new ElasticsearchContainer(ES_IMAGE)
-         .withEnv("xpack.security.enabled", "false")
-         .withEnv("discovery.type", "single-node")
-         .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
+ static final ElasticsearchContainer OS_CONTAINER = new ElasticsearchContainer(OS_IMAGE)
+         .withEnv("discovery.type", "single-node")
+         .withEnv("DISABLE_SECURITY_PLUGIN", "true")
+         .withEnv("plugins.security.disabled", "true")
+         .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
+         .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
          .withStartupTimeout(Duration.ofMinutes(3));
```

**Note:** Testcontainers' `ElasticsearchContainer` class works with OpenSearch images via `asCompatibleSubstituteFor`. This is the recommended approach documented by Testcontainers.

The `setUp()` method uses `RestClient.builder(HttpHost.create(...))` which must also be changed to use the OpenSearch transport builder, matching the production config changes.

### 3.15 Configuration Property Names -- Preserved

All existing configuration property names (`app.catalog.elasticsearch.*`, `discovery.elasticsearch.*`, `ES_HOSTS`, `ES_ALIAS`, etc.) are preserved. The property names describe the search engine role, not the vendor product. Renaming them to `opensearch` would break all existing deployments and environment configurations.

The `@ConditionalOnProperty` guards also remain unchanged:
- `app.catalog.elasticsearch.enabled`
- `discovery.text-search.engine`
- `discovery.spatial.engine`

### 3.16 EmbeddingClient and QueryEnricher -- No Change

These classes use the OpenAI-compatible HTTP API (`/v1/embeddings` and `/v1/chat/completions`). They have no dependency on Elasticsearch/OpenSearch client libraries. Zero changes required.

### 3.17 EsSearchAssembler, CatalogDocumentAssembler, GeoShapeExtractor -- No Change

These classes work with raw `Map<String, Object>` and `JsonNode` objects. They have no imports from Elasticsearch/OpenSearch client libraries. Zero changes required.

---

## 4. Complete File Change Inventory

### 4.1 Production Code Changes

| File | Change Type | Scope |
|------|-------------|-------|
| `jobs/catalog-publish-job/build.gradle` | Dependency swap | 1 line |
| `jobs/catalog-discover-job/build.gradle` | Dependency swap | 2 lines |
| `docker-compose.yml` | Image + env vars | ~10 lines |
| `config/es-index-template.json` | `dense_vector` to `knn_vector`, add `index.knn` setting | ~15 lines |
| **catalog-publish-job:** | | |
| `indexing/EsIndexingConfig.java` | Import rewrite + client construction | ~15 lines |
| `indexing/EsIndexManager.java` | Import rewrite (`ElasticsearchClient` to `OpenSearchClient`) + `defaultTemplateJson()` vector field | ~10 lines |
| `indexing/bulk/BulkIndexService.java` | Import rewrite only | ~8 lines |
| **catalog-discover-job:** | | |
| `config/EsSearchConfig.java` | Import rewrite + client construction | ~15 lines |
| `service/elasticsearch/ElasticsearchTextSearchEngine.java` | Import rewrite + exception rename | ~10 lines |
| `service/elasticsearch/ElasticsearchQueryEngine.java` | Import rewrite + exception rename | ~10 lines |
| `service/elasticsearch/EsSpatialQueryBuilder.java` | Import rewrite only | ~4 lines |
| `service/elasticsearch/EsSchemaFilterBuilder.java` | Import rewrite only | ~2 lines |

### 4.2 Test Code Changes

| File | Change Type | Scope |
|------|-------------|-------|
| `indexing/bulk/BulkIndexServiceTest.java` | Import rewrite (mocked types) | ~6 lines |
| `service/elasticsearch/EsSchemaFilterBuilderTest.java` | Import rewrite only | ~4 lines |
| `service/elasticsearch/ElasticsearchTextSearchEngineIntegrationTest.java` | Import rewrite + Docker image + client construction | ~25 lines |

### 4.3 No-Change Files (Confirmed)

These files have zero Elasticsearch client imports and require no modifications:

- `EmbeddingClient.java` (both jobs)
- `QueryEnricher.java`
- `EsSearchAssembler.java`
- `CatalogDocumentAssembler.java`
- `GeoShapeExtractor.java`
- `ElasticIndexStep.java` (uses only `BulkIndexService` and `CatalogDocumentAssembler` -- no direct client usage)
- `EsFailureConsumer.java` (uses only `BulkIndexService` and `CatalogDocumentAssembler`)
- `EsFailurePublisher.java` (Kafka only)
- `EsFailureMessage.java` (record, no ES imports)
- `EsIndexerMetrics.java` (Micrometer only)
- `DiscoveryProperties.java` (config POJO, no ES imports)
- `AppProperties.java` (config record, no ES imports)
- `AnyEsFeatureCondition.java` (Spring condition, no ES imports)
- `EsTextSearchCondition.java` (Spring condition, no ES imports)
- `config/es-synonyms.txt` (plain text, identical format)
- All `LogEvent.java` files (constants only)

---

## 5. Migration Steps (Ordered)

### Phase 1: Code Changes (no deployment)

1. **Update `build.gradle`** in both jobs: swap Elasticsearch client for OpenSearch client.
2. **Rewrite imports** in all 11 production + 3 test files listed in Section 4.
3. **Rename exception type** in `ElasticsearchTextSearchEngine` and `ElasticsearchQueryEngine`.
4. **Update client construction** in `EsIndexingConfig` and `EsSearchConfig` to use `ApacheHttpClient5TransportBuilder`.
5. **Update vector field mapping** in `config/es-index-template.json` and `EsIndexManager.defaultTemplateJson()`.
6. **Update Testcontainers** image in `ElasticsearchTextSearchEngineIntegrationTest`.
7. **Run `./gradlew compileJava`** in both jobs -- fix any compilation errors.
8. **Run `./gradlew test`** in both jobs -- fix any test failures.

### Phase 2: Local Validation

9. **Update `docker-compose.yml`**: swap image, env vars, synonym mount path.
10. **`docker compose down -v && docker compose up -d`**: start fresh OpenSearch instance.
11. **Publish test catalog** via catalog-publish-job -- verify index template created, documents indexed.
12. **Run discovery queries** -- verify BM25 text search, geo_shape spatial search both return correct results.
13. **Verify synonym expansion** -- search "EV" should match "electric vehicle".
14. **(Optional) If embedding is configured:** verify KNN vector search returns results.

### Phase 3: Production Deployment

15. **Deploy OpenSearch cluster** (single-node or multi-node per environment requirements).
16. **Deploy updated catalog-publish-job** -- it will create the index template on first publish.
17. **Trigger full re-index from PostgreSQL** -- since PG is source of truth, re-publish all catalogs. This populates the new OpenSearch indices from scratch.
18. **Deploy updated catalog-discover-job** -- it connects to OpenSearch and queries the newly populated indices.
19. **Smoke test** all search paths: text, spatial, combined, schema-filtered.
20. **Decommission old Elasticsearch cluster** after validation period.

**No blue-green or zero-downtime cutover is needed** because:
- Search is a read path with no persistent state -- a brief search outage during re-index is acceptable.
- PostgreSQL is the authoritative store -- no data lives exclusively in ES.
- The re-index populates OpenSearch from scratch; there is no ES-to-OpenSearch data migration.

---

## 6. Risk Assessment

### 6.1 Low Risk

| Risk | Mitigation |
|------|-----------|
| **Import rewriting errors** | Mechanical transformation. `compileJava` catches 100% of import errors at build time. |
| **API surface differences** | OpenSearch Java client 2.x mirrors ES 8.x API. All methods used by Discovr (`bulk`, `search`, `deleteByQuery`, `indices().create`, `indices().putIndexTemplate`, `indices().putAlias`, `indices().exists`, `indices().existsAlias`, `indices().refresh`) exist with identical signatures. |
| **Analyzer compatibility** | `standard` tokenizer, `stop`/`stemmer`/`synonym` filters are core OpenSearch features with identical configuration. |
| **geo_shape compatibility** | Same query DSL, same relation types, same GeoJSON input format. |
| **min_score compatibility** | Identical parameter on search request. |

### 6.2 Medium Risk

| Risk | Mitigation |
|------|-----------|
| **KNN scoring differences** | OpenSearch FAISS cosine similarity scoring may produce slightly different absolute score values than ES's built-in HNSW. Relative ranking should be equivalent. **Action:** Review `min_score` threshold after migration and tune if recall drops. |
| **KNN top-level `knn` syntax** | Supported since OpenSearch 2.17. We target 2.19.1. If deploying to an older OpenSearch, use `knn` inside `bool.must` instead. |
| **HNSW parameter tuning** | `ef_construction: 256, m: 16` are sensible defaults but may not be optimal for the specific dataset. **Action:** Benchmark with production data volume and tune if needed. |
| **Testcontainers image pull time** | OpenSearch image is ~800MB. First test run on CI may be slower. **Action:** Use Docker layer caching on CI. |

### 6.3 High Risk

| Risk | Mitigation |
|------|-----------|
| **None identified** | The migration is a client library swap with no business logic changes. PostgreSQL remains the source of truth. Full re-index is the recovery path for any data issue. |

---

## 7. Testing Strategy

### 7.1 Unit Tests

All existing unit tests pass after import rewriting because they mock the search client. The mock type changes from `ElasticsearchClient` to `OpenSearchClient` but the mocked method signatures are identical.

**Files:**
- `BulkIndexServiceTest.java` -- mock `OpenSearchClient`, verify `bulk()` calls
- `EsSchemaFilterBuilderTest.java` -- asserts on query DSL objects (same types under new package)

### 7.2 Integration Tests

`ElasticsearchTextSearchEngineIntegrationTest.java` already covers the critical paths against a real search engine instance:
- BM25 text search with stemming and synonym expansion
- Schema context filtering (paired tuple matching)
- Relative score threshold filtering
- Custom field list restriction
- Multi-catalog grouping
- Empty result handling

After updating the Testcontainers image to OpenSearch 2.19.1, all 15 existing test cases must pass without modification to the test logic (only import/image changes).

### 7.3 Manual Validation Checklist

| Scenario | Expected Result |
|----------|----------------|
| Publish a catalog with `updateMode: MERGE` | Documents appear in OpenSearch index |
| Publish a catalog with `updateMode: FULL` | Old documents deleted, new documents indexed |
| Text search: "electric vehicle charger" | Results returned with BM25 scoring |
| Text search: "EV" | Synonym expansion matches "electric vehicle" docs |
| Spatial search: geo_shape intersects | Results within geographic boundary returned |
| Schema context filter: `https://schema.org/EV#Charger` | Only Charger-type resources returned |
| Combined text + spatial | Intersection of text and spatial results |
| ES failure retry | Failed item re-indexed via Kafka failure topic |
| ES failure DLQ | Item exceeding max attempts routed to final DLQ |
| **(If embedding enabled)** KNN vector search | Semantically similar results returned |

### 7.4 Performance Baseline

Before and after migration, measure:
- **Indexing throughput**: documents/second via bulk API
- **p50/p95/p99 search latency**: BM25, KNN, spatial, combined
- **Synonym expansion correctness**: spot-check 10 synonym pairs

---

## 8. Rollback Plan

1. **Revert code changes** (single git revert of the migration commit).
2. **Restore `docker-compose.yml`** to use `elasticsearch:9.3.1`.
3. **`docker compose down -v && docker compose up -d`** to start fresh ES.
4. **Re-publish all catalogs** to repopulate ES indices from PostgreSQL.

Rollback is safe because:
- PostgreSQL is untouched by this migration (no schema changes, no data changes).
- Elasticsearch indices are ephemeral and fully reconstructible from PG.
- No backward-incompatible changes to Kafka topics, message formats, or API contracts.

---

## 9. Out of Scope

- **OpenSearch security configuration** (TLS, authentication, role-based access control). This design covers dev/single-node mode with security disabled, matching the current ES configuration. Production security hardening is a separate concern.
- **OpenSearch Dashboards** (replacement for Kibana). Not currently used by Discovr.
- **Multi-node cluster configuration**. The current setup is single-node; cluster topology is an operational concern.
- **Renaming Java class names** (e.g., `ElasticsearchTextSearchEngine` to `OpenSearchTextSearchEngine`). The class names describe the search engine role. Renaming them would create unnecessary churn in imports, Spring bean names, and log output without functional benefit. This can be done as a follow-up cosmetic cleanup if desired.
- **Renaming configuration property names** (e.g., `app.catalog.elasticsearch.*` to `app.catalog.opensearch.*`). This would break all existing deployments. The property names are stable identifiers, not vendor labels.
