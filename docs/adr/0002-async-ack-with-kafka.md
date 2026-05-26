# ADR-0002: Immediate ACK with Asynchronous Kafka Processing

**Date**: 2026-05-26
**Status**: accepted
**Deciders**: Beckn Discovr engineering team

## Context

The Beckn protocol specifies that `POST /discover` must return an ACK synchronously while the actual `on_discover` callback is delivered asynchronously. Discovery queries involve multiple data sources (PostgreSQL spatial queries, Elasticsearch text search) whose latency is variable and cannot be bounded within a synchronous HTTP timeout. BAPs must not be blocked waiting for query results.

## Decision

`POST /beckn/discover` validates auth and schema, publishes the request to a Kafka topic, and immediately returns `{"status":"ACK"}`. A `DiscoveryEventConsumer` processes the message asynchronously, runs the full query pipeline, and publishes the response to a second Kafka topic consumed by the `response-dispatcher`, which delivers the `on_discover` callback to the BAP.

## Alternatives Considered

### Alternative 1: Synchronous HTTP response with query results
- **Pros**: Simpler architecture — no Kafka dependency, no separate dispatcher
- **Cons**: Violates Beckn protocol contract; query latency (ES, PostGIS) is unbounded and would cause timeouts under load
- **Why not**: Protocol non-compliance and latency unpredictability under load make this untenable

### Alternative 2: Async processing backed by a database queue instead of Kafka
- **Pros**: No Kafka infrastructure requirement; polling-based retry is straightforward
- **Cons**: Database polling adds latency and load on the OLTP store; losing the ordering and replay guarantees Kafka provides
- **Why not**: Kafka is already in the infrastructure (catalog publish uses it); adding a DB queue would introduce a second async mechanism with inferior guarantees

## Consequences

### Positive
- POST /discover responds in milliseconds regardless of query complexity
- Kafka provides durable buffering — if the discover job is briefly down, requests queue up and are processed on recovery (no message loss)
- `DefaultErrorHandler` on `DiscoveryEventConsumer` retries transient failures without acknowledging the message, ensuring at-least-once delivery
- Kafka `messageId` dedup cache (Caffeine, configurable TTL) prevents duplicate processing of replayed messages

### Negative
- BAPs receive `on_discover` out-of-band; they must implement callback handling
- Debugging end-to-end requires correlating logs across three jobs using `transactionId` and `messageId`
- HTTP 409 (`AckNoCallback`) from the BAP must be treated as a non-error condition and logged only at INFO

### Risks
- If `response-dispatcher` is down for an extended period and the Kafka topic's retention window expires, messages are silently lost. Mitigated by configuring adequate retention (default 7 days) and consumer lag alerting.
