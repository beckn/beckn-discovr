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
| Domain models | `model/Context.java`, `model/Catalog.java`, `model/Item.java`, `model/Provider.java`, `model/Descriptor.java`, `model/AckResponse.java` |
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
| Kafka consumer | `messaging/consumer/EventConsumer.java` |
| HTTP callback delivery | `service/HttpService.java` |
| Beckn HTTP signature | `service/SignatureService.java` |
| Config | `config/SeekerProperties.java` · `src/main/resources/application.yml` |

---

## Beckn Protocol v2.0 — Applied (March 2026)

All three jobs have been migrated to Beckn Protocol v2.0:

### Context fields (camelCase)
`transactionId`, `messageId`, `bapId`, `bapUri`, `bppId`, `bppUri`, `networkId` (String, not List), `schemaContext`. Field `coreVersion` removed.

### Catalog/Item fields (no `beckn:` prefix)
`id`, `descriptor`, `items`, `offers`, `provider`, `itemAttributes`, `name`, `shortDesc`, `longDesc`, `images`, `networkId`

### ACK/NACK format
- ACK: `{"status":"ACK"}` — no transactionId, no timestamp
- NACK: `{"status":"NACK","error":{"errorCode":"...","errorMessage":"..."}}`
- HTTP 409 = `AckNoCallback` — log and skip, not an error

### on_discover response structure
```json
{
  "context": {"action":"on_discover","messageId":"...","bapId":"...","transactionId":"...",...},
  "message": {
    "catalogs": [{"id":"...","descriptor":{"name":"..."},"items":[...],"offers":[...]}]
  }
}
```

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

## Hard Rules — Never Violate

- **Constructor injection only** — no `@Autowired` field injection
- **Parameterized SQL only** — no string concatenation in queries
- **Secrets via `${ENV_VAR}` only** — never hardcoded in YAML
- **No `Thread.sleep()` in tests** — use deadline-based poll loops from `BaseIntegrationTest`
- **Validate callback URLs before HTTP POST** — SSRF risk
- **No `new ObjectMapper()`** — inject Spring Boot's auto-configured bean
- **Beckn v2.0 field names only** — no `beckn:` prefix, no snake_case context fields
- **Topic names from `@ConfigurationProperties`** — never hardcoded string literals
- **Kafka publish**: `kafkaTemplate.send().whenComplete(...)` — never `.get()`
- **Per-job Gradle wrappers**: run `./gradlew` from the specific job directory

---

## Agents

Five agents in `.claude/agents/` for autonomous development workflow:

| Agent | Model | Purpose |
|-------|-------|---------|
| `design` | Opus | Two proposals → scoring → Design Spec. **User approves before proceeding.** |
| `implement` | Sonnet | Implements from Design Spec with tests. Runs autonomously. |
| `review` | Opus | CRITICAL/HIGH/MEDIUM/LOW findings. APPROVE/REQUEST CHANGES/BLOCK. |
| `test-runner` | Haiku | Runs `./gradlew test`, reports pass/fail. Cheap — use freely. |
| `debug` | Sonnet | Reads failures, fixes minimally, re-tests. Max 3 rounds then reports. |

**Development Workflow:**
```
design → [USER APPROVAL] → implement → review → test-runner → debug (if failures) → done
```

For small tasks (bug fix, field rename): skip design → implement → review → test-runner.
