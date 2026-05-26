# ADR-0009: Elasticsearch Document ID Format — catalogId:resourceId

**Date**: 2026-05-26 (decision from schema redesign, commit c823013)
**Status**: accepted
**Deciders**: Beckn Discovr engineering team

## Context

Each resource indexed in Elasticsearch needs a stable, deterministic document ID so that re-indexing a resource (on catalog re-publish) updates the existing ES document rather than creating a duplicate. The original scheme used `bpp_id:resource_id`, but `bpp_id` was removed from the catalog body during the v2.0 schema redesign (ADR-0005). A multi-catalog BPP would also produce ID collisions if two catalogs contained resources with the same `resource_id`.

## Decision

The Elasticsearch document ID is formatted as `catalogId:resourceId`, enforced in `CatalogDocumentAssembler`. This composite ID is computed deterministically from the catalog's identity and the resource's identity within that catalog. FULL replace operations in Elasticsearch delete by `catalog_id` field match before re-indexing, ensuring clean replacement.

## Alternatives Considered

### Alternative 1: Use bpp_id:resource_id (original scheme)
- **Pros**: Preserved existing behavior
- **Cons**: `bpp_id` was removed from the catalog body in v2.0; two catalogs from the same BPP with overlapping resource IDs would produce ID collisions and silent overwrites
- **Why not**: `bpp_id` removal made this key invalid; multi-catalog BPPs would experience data corruption

### Alternative 2: Generate a UUID per document at index time
- **Pros**: Guaranteed uniqueness; no risk of collision
- **Cons**: Non-deterministic — re-publishing the same resource would create a new document instead of updating the existing one, leading to duplicate results until the old document is deleted
- **Why not**: Idempotent upsert requires a deterministic ID derived from the resource's natural identity

### Alternative 3: Use only resource_id as the ES document ID
- **Pros**: Simpler, matches the Beckn resource identifier
- **Cons**: Resource IDs are only unique within a catalog, not globally — two catalogs with a resource ID of `item-1` would collide
- **Why not**: Cross-catalog collisions would cause one catalog's resource to silently overwrite another's

## Consequences

### Positive
- Re-publishing a catalog always updates existing ES documents (deterministic ID)
- FULL replace is clean: delete all documents where `catalog_id = X`, then bulk index new documents with the same deterministic IDs
- Document ID encodes the catalog scope, making debugging and manual ES queries easier

### Negative
- The `:` separator must be escaped in some ES query contexts
- Changing the ID format (e.g., to a hash) requires re-indexing all catalogs

### Risks
- If `catalogId` contains `:` characters, the composite key becomes ambiguous. Validated at index time — `catalogId` values must not contain `:`.
