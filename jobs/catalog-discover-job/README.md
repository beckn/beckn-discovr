# Discovery Service

Query engine for the Beckn One Catalog Distribution System (CDS). It accepts Beckn discovery requests and returns matching catalog items from a PostgreSQL/YugabyteDB-backed store, with optional JSONPath filters, PostGIS spatial queries, and NLWeb text search.

**Stack:** Java 17, Spring Boot 3.2, PostgreSQL/PostGIS (or YugabyteDB), Kafka (optional), Micrometer/Prometheus.

---

## How it works

### High-level flow

1. **HTTP** — Requests hit `GET` or `POST` `/beckn/discover`. The controller parses the body, runs **authorization** (Beckn HTTP Signatures, optional), **schema validation** (OpenAPI/NetworkNT), then hands a `DiscoverRequest` to the discovery service.
2. **Routing** — The service inspects the request and chooses one of four **query paths** (A, B, C, or D) based on whether the request has filters, spatial constraints, and/or text search.
3. **Query** — The chosen path runs against PostgreSQL (Paths A/B/C) or the NLWeb text-search service (Path D). Results are raw catalogs/items.
4. **Post-processing** — Every path sends the result through a single **catalog pipeline** (schema filter, dedupe offers, cross-filter items/offers, remove empty catalogs).
5. **Response** — The response processor builds a `DiscoverResponse` with context and the processed catalog list (or an empty response).

**Kafka** — When `discovery.kafka.request-topic` is set, a consumer listens for discovery requests on that topic, validates them (same schema as HTTP), then calls the same discovery service. Auth is not applied on Kafka; the topic is expected to be protected by broker ACLs.

---

## Request flow (HTTP)

```
┌─────────────────────────────────────────────────────────────────────────┐
│  GET/POST /beckn/discover  (raw body + headers)                          │
└─────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  DiscoveryController.handleDiscoverRequest()                             │
│  1. Parse body → JsonNode                                                │
│  2. Set request attribute "beckn.transactionId" (for NACK in errors)     │
│  3. AuthorizationService.authorizeRequest()  [if registry auth enabled] │
│  4. DiscoveryValidationService.validateDiscoverRequest()                 │
│  5. Convert to DiscoverRequest → DiscoveryService.processDiscoveryRequest│
└─────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  DiscoveryService.processDiscoveryRequest()                              │
│  • Validate request (non-null context)                                   │
│  • Set MDC (transactionId, messageId, bapId)                             │
│  • Build QueryRequest from DiscoverRequest                               │
│  • route(qr, context) → path A, B, C, or D                               │
│  • CatalogPipeline.process(catalogs, qr)                                 │
│  • ResponseProcessor.buildResponse() or buildEmptyResponse()             │
└─────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  ResponseEntity.ok(DiscoverResponse)                                     │
└─────────────────────────────────────────────────────────────────────────┘
```

- **Authorization** — Validates Beckn HTTP Signature (Ed25519, BLAKE-512 digest). Uses `CacheService` for parsed public keys and `RegistryService` to fetch keys from the registry when missing.
- **Schema validation** — NetworkNT JSON Schema against the DiscoverRequest schema loaded from a remote OpenAPI YAML (e.g. GitHub). Ensures `message.filters.expression` is non-blank and an absolute JSONPath (starts with `$`).
- **Errors** — `GlobalExceptionHandler` (extends Spring’s `ResponseEntityExceptionHandler`) turns exceptions into Beckn NACK responses. Auth errors (e.g. 401) keep their status via the parent’s `handleErrorResponseException`; validation and other errors get 400 or 500 as appropriate.

---

## Query routing (Paths A / B / C / D)

Routing is based only on what the request contains (filters, spatial, text search). Order of checks matters.

```
                    DiscoverRequest
                           │
         ┌────────────────┼────────────────┐
         │                │                │
    hasFilters?      hasSpatial?     hasTextSearch?
         │                │                │
         ▼                ▼                ▼
┌───────────────┐  ┌───────────────┐  ┌───────────────┐
│ filters +     │  │ filters only  │  │ spatial only  │  │ neither      │
│ spatial       │  │               │  │               │  │              │
└───────┬───────┘  └───────┬───────┘  └───────┬───────┘  └──────┬────────┘
        │                  │                  │                 │
        ▼                  ▼                  ▼                 ▼
   ┌─────────┐        ┌─────────┐        ┌─────────┐       ┌─────────┐
   │ Path A  │        │ Path B  │        │ Path C  │       │ Path D  │
   │ Combined│        │ JSONPath│        │ Spatial │       │ Text    │
   │ (single │        │ only    │        │ only    │       │ search  │
   │  SQL)   │        │         │        │         │       │ (NLWeb) │
   └────┬────┘        └────┬────┘        └────┬────┘       └────┬────┘
        │                  │                  │                 │
        │ Optional.empty() │                  │                 │
        │ (no spatial      │                  │                 │
        │  conditions)     │                  │                 │
        ▼                  │                  │                 │
   Path A fallback:        │                  │                 │
   run Path B ∥ Path C     │                  │                 │
   in parallel, then       │                  │                 │
   intersect by item ID    │                  │                 │
        │                  │                  │                 │
        └──────────────────┴──────────────────┴─────────────────┘
                                      │
                                      ▼
                    Raw List<Catalog> from engine
                                      │
                                      ▼
                    CatalogPipeline.process(catalogs, qr)
                                      │
                                      ▼
                    ResponseProcessor.buildResponse() → DiscoverResponse
```

| Path | Condition | Backend | Description |
|------|-----------|---------|-------------|
| **A** | `filters` and `spatial` | PostgreSQL | Single combined SQL (JSONPath + spatial EXISTS). If the engine cannot build spatial conditions, falls back to running Path B and Path C in parallel and intersecting results by item ID in Java. |
| **B** | `filters` only | PostgreSQL | JSONPath filter on `item.payload` (GIN index). |
| **C** | `spatial` only | PostgreSQL | PostGIS spatial query via `item_location_collection` (GiST index). |
| **D** | neither (or only text) | NLWeb | Natural-language text search via NLWeb HTTP API. Runs on a dedicated executor so blocking HTTP does not tie up servlet threads. |

Path D and the Path A parallel fallback use a dedicated thread pool (`discoveryQueryExecutor`) and a configurable timeout (`discovery.postgresql.parallel-query-timeout-seconds`).

---

## Post-processing pipeline

After **every** path, the raw `List<Catalog>` is passed through `CatalogPipeline` in a fixed order:

1. **filterBySchemaContext** — Drop items that do not match the request’s `schema_context` URLs. (PostgreSQL paths already filter in SQL; this is the main filter for NLWeb/Elasticsearch.)
2. **deduplicateOffers** — Dedupe offers within each catalog by `id` / `beckn:id`.
3. **filterItemsByOfferReferences** — When the query populated offers, keep only items that are referenced by at least one offer.
4. **filterOffersByItemIds** — Remove offers that reference none of the catalog’s items.
5. **removeEmptyCatalogs** — Drop catalogs that end up with no items.

Then `ResponseProcessor.buildResponse()` (or `buildEmptyResponse()`) produces the final `DiscoverResponse`.

---

## Kafka flow

When `discovery.kafka.request-topic` is set:

1. **DiscoveryEventConsumer** receives a message (payload string).
2. **Parse** — If parsing fails, the message is **not** acknowledged (Kafka can retry / DLT).
3. **Schema validation** — Same as HTTP. If validation fails, the message **is** acknowledged to avoid infinite retries; error is logged.
4. **DiscoveryService.processDiscoveryRequest()** — Same as HTTP. On processing failure, the message is still acknowledged; retries and logging are at the service level.

No HTTP signature auth on Kafka; access control is expected at the broker (ACLs).

---

## Configuration (high level)

- **Discovery:** `discovery.*` — e.g. `discovery.postgresql.result-limit`, `discovery.postgresql.parallel-query-timeout-seconds`, `discovery.postgresql.parallel-query-workers`, `discovery.registry-auth.enabled`, `discovery.schema.url`, `discovery.nlweb.base-url`, `discovery.text-search.engine` (e.g. `nlweb`).
- **Health / metrics:** Actuator (`/actuator/health`, `/actuator/prometheus`). Admin reset of operational stats: `POST /discovery-service/health/reset-stats`.
- **Kafka:** `discovery.kafka.request-topic`, `spring.kafka.*`.

See `requirements.md` for full functional and non-functional requirements, and `src/main/java/org/beckn/discover/config/DiscoveryProperties.java` for all properties.

---

## Project layout (key packages)

| Package | Role |
|---------|------|
| `controller` | REST entry (`/beckn/discover`), health admin. |
| `consumer` | Kafka listener for discovery requests. |
| `service` | `DiscoveryService` (routing), `CacheService`, `NLWebService`, `DiscoveryMetrics`, `DiscoveryHealthIndicator`. |
| `service.authorization` | Beckn HTTP Signature validation, registry key fetch, crypto. |
| `service.validation` | Schema load (OpenAPI YAML), NetworkNT validation, filter expression checks. |
| `service.engine` | `QueryEngine`, `TextSearchEngine`, `QueryRequest`. |
| `service.postgresql` | `PostgreSQLQueryEngine`, `PostgreSQLService`, `JsonPathQueryBuilder`, `SpatialQueryBuilder`, `QueryBuilderHelper`. |
| `service.response` | `CatalogPipeline`, `CatalogProcessor`, `ResponseProcessor`. |
| `exception` | `GlobalExceptionHandler` → Beckn NACK. |

---

## References

- **Requirements:** [requirements.md](requirements.md) — FR/NFR, query routing overview, data model, SQL injection mitigations.
- **Run:** `./gradlew bootRun` (with DB and optional Kafka/registry/NLWeb configured).
