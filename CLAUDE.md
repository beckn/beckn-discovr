# Beckn Discovr — Claude Navigation Index

Catalog **discovery → query → dispatch** pipeline for the Beckn ecosystem.
BAPs send `discover` requests → Discovr queries the catalog index → delivers `on_discover` callbacks.
**Not** a catalog management service — catalog data is indexed from Beckn Catalg.

---

## Components

| Component | Path | Stack |
|-----------|------|-------|
| Catalog Discover Job | `jobs/catalog-discover-job/` | Java 17 · Spring Boot · PostgreSQL/PostGIS · Elasticsearch · Kafka |
| Catalog Publish Job | `jobs/catalog-publish-job/` | Java 17 · Spring Boot · Kafka · PostgreSQL · Elasticsearch |
| Response Dispatcher | `jobs/response-dispatcher/` | Java 17 · Spring Boot · Kafka · RestTemplate |

---

## File Map — Read These First

### Catalog Discover Job (`jobs/catalog-discover-job/src/main/java/org/beckn/discover/`)

| Task | File |
|------|------|
| REST entry point (GET + POST /beckn/discover) | `controller/DiscoveryController.java` |
| Global NACK handler | `exception/GlobalExceptionHandler.java` |
| Discovery orchestration | `service/DiscoveryService.java` |
| PostgreSQL query engine (J / J+G) | `service/postgresql/PostgreSQLQueryEngine.java` |
| PostgreSQL assembler | `service/postgresql/PostgreSQLAssembler.java` |
| SQL builder helper (JSONPath + spatial) | `service/postgresql/QueryBuilderHelper.java` |
| Provider-level offer enrichment (post-pipeline) | `service/postgresql/ProviderOfferEnricher.java` |
| Elasticsearch query engine (G / G+T / chain) | `service/elasticsearch/ElasticsearchQueryEngine.java` |
| ES schema-context pushdown filter | `service/elasticsearch/EsSchemaFilterBuilder.java` |
| Elasticsearch text search | `service/elasticsearch/ElasticsearchTextSearchEngine.java` |
| NLWeb text search | `service/nlweb/NLWebTextSearchEngine.java` |
| Query request model (J/G/T flags) | `service/engine/QueryRequest.java` |
| Response pipeline (schema filter → dedup → prune) | `service/response/CatalogPipeline.java` |
| Catalog/item normalization & offer ops | `service/response/CatalogProcessor.java` |
| on_discover response assembly | `service/response/ResponseProcessor.java` |
| Schema validation | `service/validation/DiscoveryValidationService.java` |
| Beckn auth (HTTP signatures) | `service/authorization/AuthorizationService.java` |
| Async Kafka consumer | `consumer/DiscoveryEventConsumer.java` |
| Config properties | `config/DiscoveryProperties.java` · `src/main/resources/application.yml` |
| Domain models | `model/Context.java`, `model/Catalog.java`, `model/Resource.java`, `model/Provider.java`, `model/Descriptor.java`, `model/AckResponse.java` |
| Logging constants | `logging/LogEvent.java`, `logging/MdcField.java`, `logging/BecknMdcContext.java` |
| Integration test base | `src/test/java/.../integration/BaseIntegrationTest.java` |

### Catalog Publish Job (`jobs/catalog-publish-job/src/main/java/org/beckn/catalogpublish/`)

| Task | File |
|------|------|
| Kafka consumer | `consumer/CatalogPublishConsumer.java` |
| Pipeline steps | `step/ParseStep.java`, `step/ValidateStep.java`, `step/PersistenceStep.java` |
| Cross-catalog offer resolution (Phase 3) | `step/OfferResolutionStep.java` |
| Context field extraction | `util/FieldExtractor.java` |
| HTTP push controller | `controller/CatalogPushController.java` |
| Elasticsearch doc assembler | `indexing/document/CatalogDocumentAssembler.java` |
| Item domain model (PK = `id` + `catalog_id`) | `model/Item.java`, `model/ItemId.java` |
| Config | `config/AppProperties.java` · `src/main/resources/application.yml` |

### Response Dispatcher (`jobs/response-dispatcher/src/main/java/org/beckn/seeker/`)

| Task | File |
|------|------|
| Kafka consumer | `messaging/consumer/EventListener.java` |
| HTTP callback delivery | `service/HttpService.java` |
| Beckn HTTP signature | `service/SignatureService.java` |
| Logging constants | `logging/LogEvent.java`, `logging/MdcField.java`, `logging/BecknMdcContext.java` |
| Config | `config/SeekerProperties.java` · `src/main/resources/application.yml` |

---

## Beckn Protocol v2.0 — Applied

All three jobs are fully migrated to Beckn Protocol v2.0. **No legacy v1.0 support.**

### Context fields (camelCase, `additionalProperties: false`)
`action`, `bapId`, `bapUri`, `bppId`, `bppUri`, `messageId` (uuid), `networkId` (String), `timestamp` (date-time), `transactionId` (uuid), `version` (const `"2.0.0"`). **No `domain`, `schemaContext`, `country`, `city` in context** — `schemaContext` moved to `message.intent`.

### Resource fields
`id`, `descriptor`, `resourceAttributes`, `provider`, `availableAt`, `rating`, `category`. **No `@context`/`@type` on Resource itself — those belong only on `resourceAttributes`.** **No `items` array — use `resources`.** **No `itemAttributes` — use `resourceAttributes`.** **No `networkId` on resources — only on context.**

### Offer fields
`id`, `descriptor`, `resourceIds` (not `items`), `validity` (`startDate`/`endDate`), `offerAttributes`. **No `@context`/`@type` on Offer itself — those belong only on `offerAttributes`.**

### ACK/NACK format
Wrapped in a `message` object per beckn.yaml `Ack`/`Nack*`. Both ACK and NACK echo the request's `messageId` AND `transactionId` (omitted only when the request is unparseable — never fabricated). Error object uses `code`/`message` (NOT `errorCode`/`errorMessage`).
- ACK: `{"message":{"status":"ACK","messageId":"<uuid>","transactionId":"<uuid>"}}`
- NACK: `{"message":{"status":"NACK","messageId":"<uuid>","transactionId":"<uuid>","error":{"code":"...","message":"..."}}}`
- `error.code` MUST be a canonical `ErrorCode` enum value from beckn.yaml (e.g. `SCH_SCHEMA_VALIDATION_FAILED`, `AUT_SIGNATURE_INVALID`, `CTX_MISSING_FIELD`, `NET_DOWNSTREAM_UNAVAILABLE`). No `NET_SERVICE_UNAVAILABLE`, no `SEC_*`, no `REQUEST_TOO_LARGE`.
- `/discover` HTTP responses are limited to the schema set: 200, 202, 400, 401, 403, 429, 500. Transient failures (downstream/semantic-search unavailable, schema-not-initialized) return **500** — never 503.
- HTTP 409 = `AckNoCallback` — log and skip, not an error

### Action values (from spec endpoint paths)
- Discover request: `"action": "discover"`
- on_discover callback: `"action": "on_discover"`
- Publish request: `"action": "catalog/publish"`
- on_publish callback: `"action": "catalog/on_publish"`
- Subscription request: `"action": "catalog/subscription"`
- on_subscription callback: `"action": "catalog/on_subscription"`

### on_discover response structure
```json
{
  "context": {"action":"on_discover","messageId":"...","bapId":"...","transactionId":"...",...},
  "message": {
    "catalogs": [{"id":"...","descriptor":{"name":"..."},"resources":[...],"offers":[...]}],
    "inReplyTo": {"messageId":"..."}
  }
}
```

### Schema Validation
- **Discover job**: Validates full request body against `DiscoverAction` endpoint schema from `paths["/discover"]` in beckn.yaml. Enforces `action: const: "discover"`, context structure, and intent structure in one pass.
- **Publish API (Node.js)**: Validates full body against `CatalogPublishAction` endpoint schema from `paths["/catalog/publish"]` in beckn.yaml. Enforces `action: const: "catalog/publish"`.
- Schema loaded at startup, cached (Caffeine 1hr TTL), fail-fast on load failure.

---

## on_discover Flow

```
POST /beckn/discover
  → Auth (Beckn HTTP Signature)
  → Schema validation
  → Publish to Kafka request topic → ACK {"message":{"status":"ACK","messageId":"<uuid>","transactionId":"<uuid>"}}

DiscoveryEventConsumer (async):
  → QueryEngine — routed by J/G/T criteria (see Query Routing below)
  → CatalogPipeline:
      1. Schema context filter
      2. Dedup offers
      3. Filter items by offer refs
      4. Filter offers by item ids
      5. Remove empty catalogs
  → ProviderOfferEnricher (appends provider-level offers — runs AFTER the pipeline)
  → ResponseProcessor (copy context, set action="on_discover")
  → Publish to Kafka response topic
```

### Query Routing — J/G/T combinations

`DiscoveryService` selects an engine path from three independent criteria:
**J** = JSONPath attribute filter (PostgreSQL), **G** = geo/spatial, **T** = text/semantic.

| Combo | Engine flow |
|-------|-------------|
| J | PostgreSQL JSONPath |
| G | PostgreSQL/PostGIS *or* Elasticsearch (`discovery.spatial.engine`) |
| T | Elasticsearch (BM25 / semantic) or NLWeb |
| J+G | PostgreSQL — single combined SQL |
| G+T | Elasticsearch (text + `geo_shape`) |
| J+T | chain: ES → resource IDs → PostgreSQL |
| J+G+T | chain: ES+geo → resource IDs → PostgreSQL |

Chain combos (J+T, J+G+T) require the Elasticsearch engine bean
(`discovery.spatial.engine=elasticsearch`); otherwise they degrade to J / J+G with the text
term dropped (logged + counted). **Provider-level offers** (offers with no `resourceIds`) are
resolved at search time by `ProviderOfferEnricher` after the pipeline, so they are not filtered
out by `filterOffersByResourceIds`.

```
ResponseDispatcher:
  → Consumes response topic
  → Signs with Beckn HTTP Signature
  → POST to BAP callback URL
```

---

## Build & Test

```bash
# Each job runs from its own directory (each has its own gradlew)

cd jobs/catalog-discover-job && ./gradlew test
cd jobs/catalog-publish-job && ./gradlew test
cd jobs/catalog-publish-job && ./gradlew integrationTest   # CI also runs this
cd jobs/response-dispatcher && ./gradlew test

# Run specific test class
./gradlew test --tests "org.beckn.discover.integration.DiscoveryControllerIntegrationTest"

# Compile only
./gradlew compileJava
./gradlew compileTestJava
```

## Local Docker Stack

```bash
docker network create beckn-network   # one-time setup
docker compose up -d
# catalog-discover-job: http://localhost:8082
# catalog-publish-job:  http://localhost:8085
# Postgres: localhost:5434
# Elasticsearch: localhost:9200
```

---

## Structured Logging

All three jobs use **LogstashEncoder** (structured JSON) with unified MDC fields.
Every `MdcField.java` across all 6 Java jobs (Catalg + Discovr) declares ALL constants for consistency.

### MDC Field Standard

| MDC Field | Description | Set by |
|-----------|-------------|--------|
| `transactionId` | Beckn protocol end-to-end trace ID | ALL |
| `messageId` | Beckn protocol request/response correlation | ALL |
| `catalogId` | Which catalog is being processed | Discovr Publish |
| `networkId` | Which network | ALL |
| `auth.subscriberId` | Org identity from auth header keyId first segment | ALL |
| `auth.recordId` | Key identity from auth header keyId second segment | ALL |
| `schemaType` | Resource `@type` | Discovr Publish |
| `publishTimestamp` | Epoch millis when catalog was published | Discovr Publish |
| `subscriptionId` | Matched subscription UUID | (not set in Discovr — declared for cross-service consistency) |
| `taskId` | Delivery task UUID | (not set in Discovr — declared for cross-service consistency) |
| `tags` | X-Tags header for origin tracking | ALL |

**Rules:**
- ALL `MdcField.java` files declare ALL constants (even if not set by that job)
- Fields not available at a stage are simply not set (absent from log output, not "unknown")
- Never add a field to individual log statements if it belongs in MDC — MDC auto-includes
- `auth.subscriberId` is set by `AuthorizationService` after `verifySignature()` for HTTP entry points
- `auth.subscriberId` is set by `CorrelationContext` from `context.subscriberId` in Kafka consumers

- **LogEvent constants** in `logging/LogEvent.java` — no hardcoded log strings
- **Log levels**: DEBUG=internal steps, INFO=milestones, WARN=validation failures/NACK, ERROR=unrecoverable
- **Error context**: full requestBody on validation fail, authHeader on auth fail, responseBody on callback error
- **OTel-ready**: add Java agent as JVM flag, zero code changes

---

## Elasticsearch Mapping Template

`config/es-index-template.json` — applies to `catalogs-*` index pattern.

Critical rules:
- **`item_attributes.@context` and `item_attributes.@type`** must be explicit `keyword` mappings inside the `item_attributes` object — never left to dynamic mapping
- **`catalog_validity`** is explicit object mapping with `startDate`/`endDate` as `date` type
- **`network_id`** is `keyword` (not text) — from context, not from resources
- **`item_provider_name`** is `text` with a `raw` keyword sub-field for exact-match filtering
- **`item_rateable`, `item_rating_value`, `item_rating_count`** use nullable wrappers — absent from ES doc when not in catalog data (no false defaults)
- When adding new `item_attributes.*` fields: add them as explicit mappings, never rely on dynamic templates for attributes fields

---

## Hard Rules — Never Violate

- **Constructor injection only** — no `@Autowired` field injection
- **Parameterized SQL only** — no string concatenation in queries
- **Secrets via `${ENV_VAR}` only** — never hardcoded in YAML
- **No `Thread.sleep()` in tests** — use deadline-based poll loops from `BaseIntegrationTest`
- **Validate callback URLs before HTTP POST** — SSRF risk
- **No `new ObjectMapper()`** — inject Spring Boot's auto-configured bean
- **Beckn v2.0 field names only** — `resources` not `items`, `resourceAttributes` not `itemAttributes`, `resourceIds` not `items` in offers, `networkId` only on context
- **No fabricated defaults** — don't default `@context`/`@type` on resourceAttributes; they're required publisher fields
- **Topic names from `@ConfigurationProperties`** — never hardcoded string literals
- **Kafka publish**: `kafkaTemplate.send().whenComplete(...)` — never `.get()`
- **Per-job Gradle wrappers**: run `./gradlew` from the specific job directory
- **Item PK is `(id, catalog_id)`** — never use `bpp_id` as part of the item key; `Item.from()` takes `catalogId` and `subscriberId`, not `bppId`
- **No `catalog`, `provider`, `networks`, `subscribers` tables** — these do not exist in Discovr DB; only `item` and `item_location_collection`
- **ES document ID = `catalogId:resourceId`** — format enforced in `CatalogDocumentAssembler`; never `bppId:resourceId`
- **FULL replace scoped to `catalog_id` only** — `DELETE WHERE catalog_id = :catalogId`; no `bpp_id` predicate in any delete query
- **No v1 backward compatibility** — `ContextNormalizer` deleted; `@JsonAlias` for snake_case `bpp_id`/`bap_id` removed; camelCase only
- **DefaultErrorHandler on Kafka consumer** — do not ack on transient failures; let `DefaultErrorHandler` retry; ack only after successful processing
- **`subscriberId` as Kafka message key on push path** — `CatalogPushService` sets key = `subscriberId` from `context.subscriberId` when publishing to internal Kafka topic
- **`created_by` is immutable** — `Item` has `@Column(updatable = false)` on `createdBy`; upsert logic must not overwrite it
- **`item` table stores both `created_by` and `subscriber_id`** — `created_by` = record_id (second `|`-segment of keyId, immutable ownership key); `subscriber_id` = org identity (first segment of keyId); these are two distinct columns: `created_by` for ownership checks, `subscriber_id` for org-level grouping. Never conflate them.

---

## Agents

Nine agents in `.claude/agents/` — use in sequence for any non-trivial change:

| Agent | Model | Purpose |
|-------|-------|---------|
| `github-epics` | Sonnet | Requirement/bullets → proposed Epics + tasks for [Project 52](https://github.com/orgs/beckn/projects/52); **user approves before** `gh` creates issues and sets Release/Sprint. Skill: `.claude/skills/github-epics.md`. |
| `requirements` | Sonnet | Asks clarifying questions → produces structured REQ doc in `docs/requirements/`. **Always invoke before design for new features.** |
| `design` | Opus | Asks clarifying questions → two proposals → scoring → Design Spec. **User approves before proceeding.** |
| `implement` | Sonnet | Implements from Design Spec with tests. Runs autonomously. |
| `review` | Opus | CRITICAL/HIGH/MEDIUM/LOW findings. APPROVE/REQUEST CHANGES/BLOCK. |
| `test-runner` | Haiku | Runs `./gradlew test`, reports pass/fail. Cheap — use freely. |
| `debug` | Sonnet | Reads failures, fixes minimally, re-tests. Max 3 rounds then reports. |
| `migrate` | Sonnet | Applies Beckn protocol version migrations across source + fixtures. |
| `verify` | Sonnet | Runs E2E scenarios against the live Docker stack. PASS/FAIL table. Use before/after every PR. |

**Development Workflow:**
```
requirements → [USER APPROVAL] → design → [USER APPROVAL] → implement → review → test-runner → debug (if failures) → verify → done
```

For small tasks (bug fix, field rename): implement → review → test-runner → verify.

**Regression verification:** Run `verify` agent any time to confirm no regressions against the live system.

## graphify

This project has a graphify knowledge graph at graphify-out/.

Rules:
- Before answering architecture or codebase questions, read graphify-out/GRAPH_REPORT.md for god nodes and community structure
- If graphify-out/wiki/index.md exists, navigate it instead of reading raw files
- After modifying code files in this session, run `python3 -c "from graphify.watch import _rebuild_code; from pathlib import Path; _rebuild_code(Path('.'))"` to keep the graph current
