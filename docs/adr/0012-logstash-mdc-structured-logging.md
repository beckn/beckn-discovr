# ADR-0012: Structured Logging with LogstashEncoder and Unified MDC Fields

**Date**: 2026-05-26 (standardized via commits 40523e4, 3534014)
**Status**: accepted
**Deciders**: Beckn Discovr engineering team

## Context

Beckn Discovr spans three jobs (publish, discover, dispatcher). Debugging a discovery flow requires correlating log lines across all three. Unstructured log messages require regex parsing to extract fields like `transactionId` or `messageId`. Different jobs using different MDC field names for the same concept (e.g., one calling it `txId`, another `transactionId`) makes cross-job correlation impossible without field normalization.

## Decision

All three jobs use **LogstashEncoder** (from `logstash-logback-encoder`) as the Logback encoder, producing structured JSON log lines. MDC fields are declared as constants in each job's `MdcField.java`, and **all `MdcField.java` files declare ALL constants across all jobs** (even if a job does not set a given field) to ensure consistent field naming. Log event names are constants in `LogEvent.java` — no hardcoded strings in log calls.

Unified MDC fields: `transactionId`, `messageId`, `catalogId`, `networkId`, `auth.subscriberId`, `auth.recordId`, `schemaType`, `publishTimestamp`, `subscriptionId`, `taskId`, `tags`.

Log levels follow a strict convention: DEBUG for internal steps, INFO for business milestones, WARN for validation failures/NACKs, ERROR for unrecoverable failures.

## Alternatives Considered

### Alternative 1: Plain text logging with SLF4J
- **Pros**: Simpler setup, no additional library
- **Cons**: Field extraction requires regex parsing; no structured key-value output; cross-job correlation requires string matching
- **Why not**: Log aggregation tools (Elasticsearch/Kibana, Datadog, Splunk) work best with structured JSON; plain text requires brittle parsing pipelines

### Alternative 2: OpenTelemetry traces instead of MDC
- **Pros**: Distributed tracing across all three jobs with automatic span correlation; richer than MDC
- **Cons**: Requires instrumentation agent and a tracing backend (Jaeger, Zipkin, OTLP collector); more operational complexity
- **Why not**: LogstashEncoder + MDC provides the correlation needed today with zero additional infrastructure; the codebase is designed to be OTel-ready (Java agent can be added as a JVM flag with no code changes)

### Alternative 3: Per-job MDC field naming
- **Pros**: Each job uses field names natural to its domain
- **Cons**: `transactionId` in the discover job and `txId` in the dispatcher are the same concept — log aggregation queries must account for both names
- **Why not**: Unified field names across all jobs are required for cross-job correlation queries to work correctly

## Consequences

### Positive
- A single Kibana/Datadog filter on `transactionId` surfaces all log lines from all three jobs for an end-to-end discover flow
- `auth.subscriberId` set by `AuthorizationService` after signature verification ensures the caller identity appears in all downstream log lines without passing it explicitly
- Adding OTel tracing in the future requires only a JVM agent flag — zero code changes

### Negative
- Every `MdcField.java` must declare constants for fields it does not use — requires cross-job coordination when adding a new MDC field
- MDC fields set on a thread are not automatically propagated to `CompletableFuture` worker threads; MDC snapshot must be captured before spawning futures and restored in the worker

### Risks
- A field set in the wrong MDC key (e.g., typo in a constant) produces silently missing correlation in log aggregation. Mitigated by using the `MdcField` constants exclusively — no raw string literals in MDC.put() calls.
