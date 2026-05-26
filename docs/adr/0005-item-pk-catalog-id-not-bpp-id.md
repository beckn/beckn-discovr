# ADR-0005: Item Primary Key is (id, catalog_id) — Not bpp_id

**Date**: 2026-05-26 (decision from schema redesign, commit c823013)
**Status**: accepted
**Deciders**: Beckn Discovr engineering team

## Context

The `item` table in Discovr stores resources from Beckn catalogs. The original schema used `bpp_id` as part of the item's composite key, reflecting the assumption that an item's identity is scoped to a BPP (Business Provider Platform). However, Beckn v2.0 introduces the concept of a `catalog_id` that is distinct from `bpp_id` — a single BPP can publish multiple catalogs, and catalog-level operations (FULL replace, ownership checks) must be scoped to `catalog_id`, not `bpp_id`. Using `bpp_id` in the key would incorrectly scope FULL replace operations to all catalogs for a BPP, potentially deleting items from catalogs not being updated.

## Decision

The item primary key is the composite `(id, catalog_id)`. `bpp_id` is not stored in the item row at all. Ownership metadata is tracked via two separate columns:

- `created_by` — the `record_id` (second `|`-segment of the auth header `keyId`), immutable after insert, records the originating key identity
- `subscriber_id` — the org identity (first segment of `keyId`), used for org-level grouping

`FULL` replace deletes items `WHERE catalog_id = :catalogId` — no `bpp_id` predicate.

## Alternatives Considered

### Alternative 1: Keep bpp_id as part of the composite key
- **Pros**: Reflects the BPP-centric mental model of the original Beckn spec
- **Cons**: A BPP publishing multiple catalogs would have all catalogs share the same delete scope on FULL replace, potentially wiping items from catalogs not being updated
- **Why not**: Causes cross-catalog data loss on FULL replace operations from multi-catalog BPPs

### Alternative 2: Use a surrogate UUID primary key
- **Pros**: Simple, no composite key management
- **Cons**: Upsert logic (`INSERT ... ON CONFLICT`) requires a natural key to detect existing items; without `(id, catalog_id)` as the conflict target, re-publishing a catalog would always insert duplicates
- **Why not**: Natural composite key is needed for idempotent upserts

## Consequences

### Positive
- FULL replace is safely scoped to a single catalog — `DELETE WHERE catalog_id = :catalogId` is correct regardless of how many catalogs a BPP has
- Upserts are idempotent: republishing the same catalog replaces existing items by their natural identity without duplication
- `created_by` provides immutable ownership tracking; `subscriber_id` provides org-level queries without conflating the two

### Negative
- All queries that previously used `bpp_id` as a filter must be rewritten to use `catalog_id`
- ES document ID format changed from `bpp_id:resource_id` to `catalogId:resourceId` — requires re-indexing when migrating from old schema

### Risks
- Confusing `created_by` (immutable record-level key) with `subscriber_id` (org identity) in queries could produce incorrect ownership checks. Documented as a hard rule in CLAUDE.md.
