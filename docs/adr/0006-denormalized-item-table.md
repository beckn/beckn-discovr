# ADR-0006: Denormalized Item Table — No catalog, provider, or networks Tables

**Date**: 2026-05-26 (decision from commits 2b57a82, c823013)
**Status**: accepted
**Deciders**: Beckn Discovr engineering team

## Context

Early versions of Discovr included `catalog`, `provider`, `networks`, and `subscribers` tables modeled after the Beckn domain hierarchy. In practice, these tables were never written to by the publish pipeline — all data flowed directly into the `item` table. The FK constraints between these tables added migration complexity and created phantom schema that confused developers. Discovr is a discovery engine, not a catalog management system; it does not need to maintain the full hierarchical domain model.

## Decision

The Discovr PostgreSQL schema contains exactly two tables:
- **`item`** — all resource data stored denormalized, with `catalog_id`, `catalog_payload` (full catalog JSON), `provider_payload` (provider JSON), and extracted fields for indexing
- **`item_location_collection`** — PostGIS coverage areas for spatial queries, keyed by `catalog_id`

The `catalog`, `provider`, `networks`, and `subscribers` tables are dropped (V11 migration). No FK constraints reference them.

## Alternatives Considered

### Alternative 1: Maintain normalized tables (catalog, provider, item hierarchy)
- **Pros**: Reflects the Beckn domain model; easier to query individual catalog or provider metadata
- **Cons**: Requires joins on every discovery query; the publish pipeline never used these tables in practice; maintaining FK constraints slows upsert transactions
- **Why not**: The publish pipeline proved that the normalized model added no value — data always ended up fully denormalized in the item row

### Alternative 2: Use a document database (MongoDB) instead of PostgreSQL
- **Pros**: Native JSON storage matches the Beckn payload structure; no schema migration needed as the payload evolves
- **Cons**: Loses PostGIS for spatial queries; PostgreSQL JSONB is mature and provides similar flexibility; transactional upserts are well-understood
- **Why not**: PostGIS is a hard requirement for geospatial discovery; switching databases is a larger infrastructure change than the benefit warrants

## Consequences

### Positive
- Zero join overhead on discovery queries — all item data is in a single row
- Schema migrations are simpler — adding a new field means adding a column to `item`, not coordinating changes across multiple related tables
- FULL replace is a single `DELETE WHERE catalog_id = :catalogId` — no cascading FK deletes

### Negative
- Catalog-level metadata (name, descriptor) is stored redundantly in every item row for that catalog
- Updating catalog-level metadata (e.g., a catalog name change) requires touching all item rows for that catalog, not a single catalog row

### Risks
- JSONB payload size per row can be large for catalogs with rich resource attributes. Mitigated by Elasticsearch serving as the text search store (PostgreSQL is queried by filter/spatial only, not scanned for text).
