# Discovery Service v2 — Requirements

## Overview

The `discovery-service` is the query engine for the Beckn One Catalog Distribution System (CDS). It receives structured discovery requests and returns matching catalog items from a PostgreSQL/YugabyteDB-backed store. The service is designed with **database portability**, **observability**, **security**, and **extensibility** as first-class concerns.

---

## Functional Requirements

### FR-1: Query Engine

| ID | Requirement |
|----|-------------|
| FR-1.1 | Support **JSONPath filter queries** against item payloads (uses PostgreSQL GIN index on `item.payload`) |
| FR-1.2 | Support **PostGIS spatial queries** using pre-indexed geometry from `item_location_collection` (GiST index on `geom`) |
| FR-1.3 | Support **combined queries** (JSONPath + spatial) in a single SQL round-trip where possible |
| FR-1.4 | Support **NLWeb / text search** queries as a fallback path |
| FR-1.5 | All spatial operations must be restricted to an explicit allow-list: `s_dwithin`, `s_intersects`, `s_contains`, `s_within`, `s_disjoint`, `s_overlaps`, `s_crosses`, `s_touches`, `s_equals` |
| FR-1.6 | Multiple spatial constraints in a single request are ANDed (all must match) |

### FR-2: Spatial Data Source

| ID | Requirement |
|----|-------------|
| FR-2.1 | Spatial queries MUST query `item_location_collection(item_id, path, geom)` — not the raw `item.payload` JSONB column |
| FR-2.2 | `item_location_collection.geom` stores pre-parsed GPS points and polygons written by `catalog-publish-job` |
| FR-2.3 | Distance-based operations (`s_dwithin`) MUST use `::geography` cast for metre-accurate computation |
| FR-2.4 | Topological operations (`s_contains`, `s_within`, etc.) use planar geometry |

### FR-3: Schema Filtering

| ID | Requirement |
|----|-------------|
| FR-3.1 | Requests may specify `context.schema_context` URLs; the query must filter `item.context_url IN (...)` |
| FR-3.2 | Requests may specify item types; the query must filter `item.type IN (...)` |

---

## Non-Functional Requirements

### NFR-1: Performance

| ID | Requirement |
|----|-------------|
| NFR-1.1 | Spatial queries MUST use the GiST index on `item_location_collection.geom` — no runtime geometry extraction from JSONB |
| NFR-1.2 | EXISTS subquery pattern (not JOIN) for spatial filtering — avoids GROUP BY on JSONB payload |
| NFR-1.3 | Combined (filter + spatial) queries run as a single SQL statement to avoid two round-trips and Java-side intersection |
| NFR-1.4 | JSONPath queries must use the GIN index on `item.payload` |
| NFR-1.5 | Query result limit is configurable via `discovery.postgresql.result-limit` |
| NFR-1.6 | Parallel query timeout is configurable via `discovery.postgresql.parallel-query-timeout-seconds` |

### NFR-2: Security — SQL Injection Prevention

| ID | Requirement |
|----|-------------|
| NFR-2.1 | **All SQL text must be composed exclusively from compile-time constants** in `QueryBuilderHelper` |
| NFR-2.2 | **All user-supplied values** (filter expressions, GeoJSON geometry, distance metres, schema URLs, item types) must be passed as JDBC `?` bind parameters |
| NFR-2.3 | Spatial function names (`ST_DWithin`, etc.) must come from the `OPERATIONS` allow-list in `SpatialQueryBuilder` — never from user input |
| NFR-2.4 | `EXPLAIN ANALYZE` execution must be gated by a property (`discovery.postgresql.log-explain-analyze`) and disabled in production |

### NFR-3: Observability

| ID | Requirement |
|----|-------------|
| NFR-3.1 | Every query execution must log `start`, `success` (with `durationMs` and `rows`), and `failure` (with error) using **dot-notation structured keys** |
| NFR-3.2 | Performance metrics (duration, row count) must be written to a dedicated `org.beckn.discover.performance` logger, configurable independently |
| NFR-3.3 | `transactionId` from the request context must appear in all log lines for a request |
| NFR-3.4 | Skipped/invalid spatial conditions must be logged at `WARN` with a `reason=` key |
| NFR-3.5 | `DiscoveryMetrics` must track: total requests, success count, failure count, total processing time, average processing time |

### NFR-4: Resilience

| ID | Requirement |
|----|-------------|
| NFR-4.1 | JSONPath queries must retry up to 3 times with 1-second backoff (Spring `@Retryable`) |
| NFR-4.2 | `IllegalArgumentException` (validation errors) must bypass the retry budget |
| NFR-4.3 | A single invalid spatial constraint must not abort the entire spatial query — remaining constraints must still execute |
| NFR-4.4 | If the combined query (Path A) cannot build spatial conditions, fall back to the parallel two-query approach |

---

## Architectural Requirements

### AR-1: Database Portability

| ID | Requirement |
|----|-------------|
| AR-1.1 | The query layer must be **abstracted behind interfaces** so that alternate backends (Elasticsearch, OpenSearch, etc.) can be added with minimal code changes |
| AR-1.2 | `DiscoveryService` routes to the correct backend; it must not contain backend-specific SQL or query logic |
| AR-1.3 | All SQL constants must live in `QueryBuilderHelper` — builders (`JsonPathQueryBuilder`, `SpatialQueryBuilder`) only compose constants, never write raw SQL strings |

### AR-2: Extensibility

| ID | Requirement |
|----|-------------|
| AR-2.1 | Adding a new spatial operation requires only a new entry in `SpatialQueryBuilder.OPERATIONS` |
| AR-2.2 | Adding a new query backend (e.g. Elasticsearch) requires: (a) a new service implementing the same method signatures, (b) a new routing condition in `DiscoveryService.executeQuery()` |
| AR-2.3 | Schema filters (type, context_url) are applied uniformly via `QueryTemplate.schemaFilters()` |

### AR-3: Spring Best Practices

| ID | Requirement |
|----|-------------|
| AR-3.1 | All beans use constructor injection (not field injection) |
| AR-3.2 | Retry logic uses `@Retryable` and `@EnableRetry` — no manual retry loops |
| AR-3.3 | Properties are typed via `@ConfigurationProperties` classes (`DiscoveryProperties`) |
| AR-3.4 | Integration tests use `@SpringBootTest` with TestContainers (PostGIS) — no mocking of the database |
| AR-3.5 | SQL migrations for tests live in `src/test/resources/sql/` and are applied via `ScriptUtils` |

---

## Query Routing Overview

```
DiscoveryRequest
       │
       ├── filters only        → Path B: JSONPath query (GIN index)
       ├── spatial only        → Path C: EXISTS spatial query (GiST index)
       ├── filters + spatial   → Path A: Combined single SQL (GIN + GiST)
       │                                 Falls back to Path B ∥ Path C + Java intersection
       └── neither             → Path D: NLWeb text search
```

---

## Data Model

### `item` table
```
id          TEXT PK
catalog_id  TEXT FK → catalog(id)
payload     JSONB        ← GIN index (jsonb_path_ops)
type        TEXT         ← filtered by schema type
context_url TEXT         ← filtered by schema_context
updated_at  TIMESTAMPTZ  ← ORDER BY
```

### `item_location_collection` table
```
item_id  TEXT FK → item(id) ON DELETE CASCADE
path     TEXT             (e.g. "$/beckn:availableAt[0]/geo/gps")
geom     GEOMETRY(Geometry, 4326)  ← GiST index
PRIMARY KEY (item_id, path)
```

Written by `catalog-publish-job / GeometryExtractor`. One row per GPS point or polygon found anywhere in the item payload.

---

## SQL Injection Risk Surface — Mitigations

| Surface | Mitigation |
|---------|-----------|
| JSONPath expression | Passed as `CAST(? AS jsonpath)` — PostgreSQL validates syntax, value never in SQL text |
| Spatial `op` name | Validated against `OPERATIONS` allow-list; only compile-time constant function names in SQL |
| GeoJSON geometry | Serialised to string, passed as `ST_GeomFromGeoJSON(?::text)` — value is a `?` |
| Distance metres | Passed as numeric `?` |
| Schema type / context_url lists | Built via `buildInClause()` using one `?` per value |
| LIMIT value | Passed through `sanitizeLimit()` — only accepts positive integers |

---

## Future Integrations

| Integration | Approach |
|-------------|---------- |
| Elasticsearch | New `ElasticsearchService` implementing same method signatures; new `Path E` in `DiscoveryService.executeQuery()` |
| OpenSearch | Same as ES — swap the client, reuse routing |
| Vector / semantic search | New path alongside NLWeb (Path D), or as a secondary re-ranking step |
| Spatial index on YugabyteDB | Already compatible — GiST on GEOMETRY is supported in YugabyteDB 2.14+ |
