# ADR-0013: Dedicated I/O Thread Pool for Parallel Discovery Queries

**Date**: 2026-05-26 (decision from commits 745c3b2, 169, 170 perf series)
**Status**: accepted
**Deciders**: Beckn Discovr engineering team

## Context

The `DiscoveryService` executes up to three queries in parallel (filter query, spatial query, and text search) and intersects their result sets. When neither filters nor spatial conditions are present, text search runs alone. Early implementations used `CompletableFuture.supplyAsync()` without a custom executor, which runs on the JVM's `ForkJoinPool.commonPool()`. The common pool is shared across all `CompletableFuture` and parallel stream operations in the JVM — blocking I/O operations on it (JDBC, ES HTTP) can starve unrelated operations and cause thread starvation under load.

## Decision

Parallel discovery queries run on a dedicated `ExecutorService` named `discoveryQueryExecutor`, configured via `discovery.postgresql.parallel-query-workers` (default: 4). This pool is used exclusively for the parallel filter + spatial query fan-out in `DiscoveryService`. MDC context (transactionId, messageId, etc.) is captured from the calling thread before submitting tasks, and each worker restores and clears MDC around its execution.

## Alternatives Considered

### Alternative 1: Use ForkJoinPool.commonPool() (default CompletableFuture behavior)
- **Pros**: Zero configuration; works out of the box
- **Cons**: Shared pool — blocking I/O in query threads can starve other async operations in the JVM; pool size defaults to `availableProcessors - 1` which may be too small for I/O-bound workloads
- **Why not**: Under load testing, common pool saturation caused query timeouts on the filter path even when ES was responsive; dedicated pool isolates the discovery query workload

### Alternative 2: Virtual threads (Java 21 Project Loom)
- **Pros**: No thread-pool tuning needed; blocking I/O is automatically suspended and resumed; scales to thousands of concurrent queries
- **Cons**: The project targets Java 17; virtual threads are not available in Java 17 without preview flags
- **Why not**: Java 17 compatibility is a hard requirement; virtual threads are a future migration option when the JDK baseline is raised

### Alternative 3: Reactive programming (Spring WebFlux + R2DBC)
- **Pros**: Non-blocking from end to end; no thread pools for I/O; scales with few OS threads
- **Cons**: Requires replacing Spring MVC with WebFlux and JDBC with R2DBC; PostGIS support for R2DBC is immature; large refactor for uncertain benefit at current scale
- **Why not**: Switching the entire web layer and data access layer is a major architectural change; the dedicated thread pool provides adequate isolation at lower cost

## Consequences

### Positive
- Query thread pool is isolated from the Spring MVC request-handling threads — a slow ES query does not block HTTP handling
- Pool size is tunable via `discovery.postgresql.parallel-query-workers` without recompilation
- MDC propagation is explicit and correct — all parallel query threads carry the same `transactionId` and `messageId` as the originating request

### Negative
- Thread pool adds operational configuration — pool exhaustion under burst traffic causes query queuing rather than immediate execution
- MDC capture-restore pattern adds boilerplate around every `CompletableFuture.supplyAsync()` call in the service

### Risks
- If `parallelQueryWorkers` is set too low, parallel path queries queue up and the `parallelQueryTimeoutSeconds` deadline expires before results are available. Mitigated by monitoring thread pool queue depth and tuning the worker count to match the expected parallelism level.
