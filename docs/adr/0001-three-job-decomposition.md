# ADR-0001: Three-Job Decomposition (Publish / Discover / Dispatcher)

**Date**: 2026-05-26
**Status**: accepted
**Deciders**: Beckn Discovr engineering team

## Context

Beckn Discovr must handle three fundamentally different concerns: indexing catalogs from BPPs, answering discovery queries from BAPs, and delivering `on_discover` callbacks back to those BAPs. These concerns have different scaling profiles, failure domains, and deployment cadences. A single monolithic service would couple them, making independent scaling and fault isolation impossible.

## Decision

We decompose Discovr into three separate Spring Boot jobs, each deployed independently:

- **catalog-publish-job** — consumes catalog publications from Kafka, persists to PostgreSQL + Elasticsearch
- **catalog-discover-job** — serves `/beckn/discover`, queries PostgreSQL/ES, publishes results to Kafka
- **response-dispatcher** — consumes the Kafka response topic, signs payloads, delivers `on_discover` to BAP callback URLs

## Alternatives Considered

### Alternative 1: Single monolithic service
- **Pros**: Simpler deployment, no inter-service coordination, single codebase
- **Cons**: Catalog indexing load (bulk writes) would compete with query traffic; a crash during a large catalog publish would also take down the discovery API
- **Why not**: Publish workloads are bursty and write-heavy while discovery is steady read traffic — sharing a process couples their resource demands and failure modes

### Alternative 2: Two services (publish+discover combined, dispatcher separate)
- **Pros**: Reduces process count by one, simpler dependency graph
- **Cons**: Catalog publish is a long-running Kafka consumer; coupling it to the synchronous HTTP service complicates thread-pool management and graceful shutdown
- **Why not**: The query-engine thread pools for text search (ES, NLWeb) are tuned separately from the Kafka consumer concurrency; combining them forces shared tuning compromises

## Consequences

### Positive
- Each job scales independently (e.g., run multiple dispatcher replicas without scaling publish)
- A crash in the dispatcher does not affect discovery availability — messages accumulate in Kafka and are processed when the dispatcher recovers
- Each job has its own Gradle wrapper, dependency set, and CI pipeline step

### Negative
- Three separate deployment artefacts and Docker images to manage
- End-to-end tracing requires correlating logs across three jobs via `transactionId` / `messageId` MDC fields
- Integration tests that span all three jobs require a full Docker Compose stack

### Risks
- Kafka becomes a single point of coupling — if the response topic backs up, `on_discover` latency degrades silently. Mitigated by monitoring consumer lag and setting alert thresholds.
