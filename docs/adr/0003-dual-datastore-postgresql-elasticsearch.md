# ADR-0003: Dual Datastore — PostgreSQL/PostGIS for Spatial/Filter, Elasticsearch for Text Search

**Date**: 2026-05-26
**Status**: accepted
**Deciders**: Beckn Discovr engineering team

## Context

Discovery requests arrive with different intent shapes: structured filter queries (category, schema context, provider, availability), geospatial queries (items available within a radius), and free-text queries (natural language product search). No single datastore serves all three shapes equally well. PostgreSQL with PostGIS excels at structured filtering and geospatial indexing. Elasticsearch excels at full-text BM25 scoring and can store denormalized documents for fast retrieval.

## Decision

We use two datastores in parallel, each owned by a different query path:

- **PostgreSQL + PostGIS** — primary store for all structured data. Item rows store `geom` (PostGIS geometry) from `availableAt` locations. All filter queries and spatial queries run here. `item_location_collection` stores coverage areas as PostGIS geometry.
- **Elasticsearch** — secondary store for text search. Each item is indexed as a flat document with a `full_text_blob` field. Text queries run entirely in ES; results are enriched with offers from PostgreSQL.

The `DiscoveryService` routes requests to the appropriate engine based on the presence of filters, spatial conditions, and free text in the request intent.

## Alternatives Considered

### Alternative 1: PostgreSQL only (full-text via `tsvector`)
- **Pros**: Single datastore, simpler ops, no ES dependency
- **Cons**: PostgreSQL full-text search cannot match Elasticsearch's BM25 scoring, synonym filters, custom analyzers, or vector KNN search; geospatial and text queries would require expensive cross-type joins
- **Why not**: Inferior text search quality and no path to semantic/vector search

### Alternative 2: Elasticsearch only
- **Pros**: Single datastore, ES handles both text and geospatial queries
- **Cons**: ES geo_shape queries are less mature than PostGIS for polygon coverage areas; transactional integrity on upserts is weaker; FULL replace (delete all items for a catalog) is riskier without ACID guarantees
- **Why not**: PostGIS is the industry standard for spatial queries; losing ACID on catalog-scoped deletes is a data integrity risk

### Alternative 3: Separate services per query type
- **Pros**: Each datastore is behind a dedicated service interface
- **Cons**: Adds inter-service HTTP calls on the hot query path, increasing latency
- **Why not**: Both datastores are accessed in-process via JDBC and the ES REST client; the indirection is unnecessary overhead

## Consequences

### Positive
- Each datastore is tuned for its workload — ES connection pool and socket timeouts are separate from JDBC pool settings
- Spatial indexes (PostGIS GIST) give sub-millisecond radius queries regardless of catalog size
- ES text search supports pluggable engines (`native-els`, `els-semantic-search`, `nlweb`) switchable via `discovery.text-search.engine` config
- ES documents are denormalized (no joins) for high-throughput text search

### Negative
- Two stores to operate, back up, and keep in sync
- A failed ES bulk index after a successful PostgreSQL upsert leaves the stores temporarily inconsistent (noted as a known gap in code comments)
- Changing the ES index schema (new fields, analyzer changes) requires recreating the index and re-indexing all catalogs

### Risks
- Divergence between PostgreSQL and Elasticsearch data is possible if the publish pipeline crashes mid-way. Mitigated by treating Kafka consumer offsets as the commit point — a crash causes a retry that re-indexes from Kafka.
