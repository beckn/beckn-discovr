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
| PostgreSQL query engine | `service/postgresql/PostgreSQLQueryEngine.java` |
| PostgreSQL assembler | `service/postgresql/PostgreSQLAssembler.java` |
| Elasticsearch text search | `service/elasticsearch/ElasticsearchTextSearchEngine.java` |
| NLWeb text search | `service/nlweb/NLWebTextSearchEngine.java` |
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
| Context field extraction | `util/FieldExtractor.java` |
| HTTP push controller | `controller/CatalogPushController.java` |
| Elasticsearch doc assembler | `indexing/document/CatalogDocumentAssembler.java` |
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

## Beckn Protocol v2.1 — Applied (March 2026)

All three jobs are fully migrated to Beckn Protocol v2.1. **No legacy v2.0/v1.0 support.**

### Context fields (camelCase, `additionalProperties: false`)
`action`, `bapId`, `bapUri`, `bppId`, `bppUri`, `messageId` (uuid), `networkId` (String), `timestamp` (date-time), `transactionId` (uuid), `version` (const `"2.0.0"`), `ttl`, `try`, `lineage`. **No `domain`, `schemaContext`, `country`, `city` in context** — `schemaContext` moved to `message.intent`.

### Resource fields (v2.1 — no `beckn:` prefix, no `items`)
`@context`, `@type` (`"beckn:Resource"`), `id`, `descriptor`, `resourceAttributes`, `provider`, `availableAt`, `rating`, `category`. **No `items` array — use `resources`.** **No `itemAttributes` — use `resourceAttributes`.** **No `networkId` on resources — only on context.**

### Offer fields
`@type` (`"beckn:Offer"`), `id`, `descriptor`, `resourceIds` (not `items`), `validity` (`startDate`/`endDate`), `offerAttributes`

### ACK/NACK format
- ACK: `{"status":"ACK"}` — no transactionId, no timestamp
- NACK: `{"status":"NACK","error":{"errorCode":"...","errorMessage":"..."}}`
- HTTP 409 = `AckNoCallback` — log and skip, not an error

### Action values (from spec endpoint paths)
- Discover request: `"action": "discover"`
- on_discover callback: `"action": "on_discover"`
- Publish request: `"action": "catalog/publish"`
- on_publish callback: `"action": "catalog/on_publish"`

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
  → Publish to Kafka request topic → ACK {"status":"ACK"}

DiscoveryEventConsumer (async):
  → QueryEngine (PostgreSQL / Elasticsearch / NLWeb)
  → CatalogPipeline:
      1. Schema context filter
      2. Dedup offers
      3. Filter items by offer refs
      4. Filter offers by item ids
      5. Remove empty catalogs
  → ResponseProcessor (copy context, set action="on_discover")
  → Publish to Kafka response topic

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

All three jobs use **LogstashEncoder** (structured JSON) with unified MDC fields:

| MDC Field | Set by | Description |
|-----------|--------|-------------|
| `correlationId` | BecknMdcContext | UUID per processing unit |
| `transactionId` | BecknMdcContext | From Beckn context |
| `messageId` | BecknMdcContext | From Beckn context |
| `bapId`, `bapUri` | BecknMdcContext | BAP identifiers |
| `bppId`, `bppUri` | BecknMdcContext | BPP identifiers |
| `networkId` | BecknMdcContext | From context.networkId |
| `action` | BecknMdcContext | Beckn action value |

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
- **Beckn v2.1 field names only** — `resources` not `items`, `resourceAttributes` not `itemAttributes`, `resourceIds` not `items` in offers, `@type: "beckn:Resource"`, `networkId` only on context
- **No fabricated defaults** — don't default `@context`/`@type` on resourceAttributes; they're required publisher fields
- **Topic names from `@ConfigurationProperties`** — never hardcoded string literals
- **Kafka publish**: `kafkaTemplate.send().whenComplete(...)` — never `.get()`
- **Per-job Gradle wrappers**: run `./gradlew` from the specific job directory

---

## Agents

Eight agents in `.claude/agents/` — use in sequence for any non-trivial change:

| Agent | Model | Purpose |
|-------|-------|---------|
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
