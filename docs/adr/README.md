# Architecture Decision Records

This directory captures architectural decisions for Beckn Discovr. Each ADR records the context, decision, alternatives considered, and consequences for a significant architectural choice.

New decisions: copy `template.md`, number sequentially, and add a row to the table below.

| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [0001](0001-three-job-decomposition.md) | Three-Job Decomposition (Publish / Discover / Dispatcher) | accepted | 2026-05-26 |
| [0002](0002-async-ack-with-kafka.md) | Immediate ACK with Asynchronous Kafka Processing | accepted | 2026-05-26 |
| [0003](0003-dual-datastore-postgresql-elasticsearch.md) | Dual Datastore — PostgreSQL/PostGIS for Spatial/Filter, Elasticsearch for Text Search | accepted | 2026-05-26 |
| [0004](0004-beckn-v2-no-backward-compat.md) | Full Migration to Beckn Protocol v2.0 — No v1.0 Backward Compatibility | accepted | 2026-05-26 |
| [0005](0005-item-pk-catalog-id-not-bpp-id.md) | Item Primary Key is (id, catalog_id) — Not bpp_id | accepted | 2026-05-26 |
| [0006](0006-denormalized-item-table.md) | Denormalized Item Table — No catalog, provider, or networks Tables | accepted | 2026-05-26 |
| [0007](0007-registry-callback-url-resolution.md) | Registry-Based Callback URL Resolution in Response Dispatcher | accepted | 2026-05-26 |
| [0008](0008-pluggable-text-search-engine.md) | Pluggable Text Search Engine via Configuration | accepted | 2026-05-26 |
| [0009](0009-es-document-id-catalogid-resourceid.md) | Elasticsearch Document ID Format — catalogId:resourceId | accepted | 2026-05-26 |
| [0010](0010-full-merge-replace-strategy.md) | FULL Replace and MERGE (RFC 7396) as Catalog Update Strategies | accepted | 2026-05-26 |
| [0011](0011-constructor-injection-only.md) | Constructor Injection Only — No @Autowired Field Injection | accepted | 2026-05-26 |
| [0012](0012-logstash-mdc-structured-logging.md) | Structured Logging with LogstashEncoder and Unified MDC Fields | accepted | 2026-05-26 |
| [0013](0013-dedicated-io-thread-pool-parallel-queries.md) | Dedicated I/O Thread Pool for Parallel Discovery Queries | accepted | 2026-05-26 |
