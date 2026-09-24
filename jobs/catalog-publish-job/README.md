# Catalog Publish Job

Ingests catalog data and indexes it for discovery. Consumes from Kafka, and also exposes two HTTP endpoints for catalog ingestion.

**Stack:** Java 17, Spring Boot, Kafka, PostgreSQL, Elasticsearch.

---

## HTTP Endpoints

| Method | Path | Beckn spec status | Description |
|--------|------|--------------------|--------------|
| POST | `/catalog/push` | **Additional, actively used** — not a `beckn.yaml` action | Discovr-internal ingestion endpoint. This is the live path by which the crawler plugin pushes catalog files (already-indexed data) into Discovr, returning `200 Ack` immediately and processing asynchronously through the publish pipeline (`ParseStep` → `ValidateStep` → `PersistenceStep`, plus `OfferResolutionStep`). A `catalog/on_publish` callback follows separately. |
| POST | `/catalog/on_pull` | **Beckn-spec-compliant, unused — likely to be removed** | Correctly implements the spec's `catalog/on_pull` callback (the async result of a prior `catalog/pull`), returning `200 Ack` and processing via `CatalogPullCallbackService`. Nothing in the current pipeline issues a `catalog/pull` request, so this endpoint sees no live traffic today; don't build new integrations against it. |

Both endpoints return spec-shaped ACK/NACK bodies (`{"message":{"status":"ACK"|"NACK",...}}`) — see [`CatalogPushController`](src/main/java/org/beckn/catalogpublish/controller/CatalogPushController.java).

See the [Discovr API reference](../../docs/reference/USER_GUIDE.md#api-reference) for the full cross-job endpoint list.

---

## Kafka

The [`CatalogPublishConsumer`](src/main/java/org/beckn/catalogpublish/consumer/CatalogPublishConsumer.java) also ingests catalog payloads from a Kafka topic and runs them through the same pipeline steps.

See `src/main/java/org/beckn/catalogpublish/config/AppProperties.java` and `src/main/resources/application.yml` for configuration.
