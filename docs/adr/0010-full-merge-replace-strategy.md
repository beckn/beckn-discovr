# ADR-0010: FULL Replace and MERGE (RFC 7396) as Catalog Update Strategies

**Date**: 2026-05-26 (decision from commit af07f39, c823013)
**Status**: accepted
**Deciders**: Beckn Discovr engineering team

## Context

BPPs publish catalog updates in two modes: a full catalog snapshot (entire catalog re-published, all previous data replaced) and incremental updates (only changed resources sent, others remain as-is). A naive "always replace" approach would wipe all items when an incremental update arrived. A naive "always merge" approach would leave stale items from a previous full publish that were removed from the catalog. The two modes require different update semantics, and the publish pipeline must distinguish between them.

## Decision

The catalog publish pipeline supports two explicit modes, selected by a `publishDirectives` field in the Beckn message:

- **FULL** — the incoming catalog replaces the entire previous state for that `catalog_id`. Implementation: `DELETE FROM item WHERE catalog_id = :catalogId` in PostgreSQL; bulk delete by `catalog_id` in Elasticsearch; then insert/index all resources in the incoming payload.
- **MERGE** — the incoming resources are merged into the existing catalog using RFC 7396 JSON Merge Patch semantics. A `null` value in the patch deletes the corresponding field from the stored document. Resources not mentioned in the patch are left unchanged.

Both modes are scoped exclusively to `catalog_id` — no cross-catalog side effects.

## Alternatives Considered

### Alternative 1: Always FULL replace
- **Pros**: Simplest implementation — no merge logic needed; always consistent
- **Cons**: BPPs publishing large catalogs with small incremental changes would re-transmit the entire catalog on every update, adding unnecessary load on both publisher and Discovr
- **Why not**: Incremental publishing is a practical requirement for BPPs with thousands of resources

### Alternative 2: Always MERGE with a separate "delete resource" endpoint
- **Pros**: Simpler Kafka message format — no mode flag; deletions are explicit API calls
- **Cons**: Delete calls can be lost; there is no way to do a clean catalog reset without re-sending every resource individually
- **Why not**: Beckn protocol uses a single `catalog/publish` action for all updates; a separate delete endpoint deviates from the protocol

### Alternative 3: Event sourcing — store all versions and derive current state
- **Pros**: Full audit trail; FULL and MERGE become projections over the same event log
- **Cons**: Significant complexity increase; query performance requires materializing the current state anyway
- **Why not**: Overkill for the current scale and requirements; the Kafka topic itself provides sufficient event history

## Consequences

### Positive
- BPPs can choose the right mode for their workflow — FULL for reliable snapshot semantics, MERGE for bandwidth-efficient incremental updates
- RFC 7396 null-delete semantics allow removing fields from existing resources without a separate delete operation
- Cross-catalog offers are resolved after MERGE/FULL persistence (Phase 3: `OfferResolutionStep`), so offers referencing resources in other catalogs are applied correctly regardless of publish mode

### Negative
- FULL replace involves a database delete followed by inserts — the window between delete and insert completion means a discovery query could momentarily return zero results for that catalog
- MERGE requires careful ordering: the existing payload must be fetched from the DB, patched, and saved transactionally to avoid lost updates under concurrent publishes for the same catalog

### Risks
- A failed ES bulk delete during FULL replace (after PostgreSQL delete succeeded) leaves PostgreSQL and ES out of sync until the next publish. Mitigated by: the Kafka message is not acknowledged until all persistence steps complete; a re-publish will trigger another FULL replace that corrects the state.
