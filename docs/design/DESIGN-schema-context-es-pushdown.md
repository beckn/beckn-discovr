# Design: Push schemaContext Filtering into Elasticsearch Queries

**Status:** PROPOSED  
**Issue:** [beckn/beckn-discovr#116](https://github.com/beckn/beckn-discovr/issues/116)  
**Date:** 2026-04-09

---

## Problem

When a BAP sends a discover request with `schemaContext` filtering, ES returns ALL matching documents regardless of schema context. The filtering only happens post-ES in `CatalogPipeline.step1FilterBySchemaContext()`. At scale this wastes ES I/O, memory, and CPU. It also breaks pagination (ES `size` doesn't account for post-filter discard).

PostgreSQL paths already push schema filtering into SQL WHERE clauses. ES paths do not.

## Current Flow

```
BAP request (schemaContext: ["https://schema.org/Product#GroceryItem"])
  → ES query (text/semantic/spatial — NO schema filter)
  → Returns ALL matching docs (100s/1000s)
  → CatalogPipeline.step1: discard docs where @context/@type ∉ schemaContext
  → Return filtered subset
```

## Proposed Flow

```
BAP request (schemaContext: ["https://schema.org/Product#GroceryItem"])
  → ES query (text/semantic/spatial + terms filter on context+type)
  → Returns ONLY matching docs
  → CatalogPipeline.step1: SKIPPED (engine already filtered)
  → Return results
```

---

## Design A: Centralized Schema Filter Query Builder + Conditional Pipeline Skip (RECOMMENDED)

**Score: 5.00 / 5.00**

### Core Idea

Introduce a stateless `EsSchemaFilterBuilder` utility that builds ES filter queries from `QueryRequest`, inject those into every ES search site as `bool.filter` or `knn.filter` clauses, and make `CatalogPipeline.step1FilterBySchemaContext` conditional — skipped for ES/PG paths, kept only for NLWeb.

### Paired Tuple Matching (Critical)

SchemaContext URLs can have different base URLs:
```json
["https://schema.org/Product#GroceryItem", "https://beckn.org/Mobility#RideService"]
```

A simple independent `terms` filter would allow cross-matches. The correct approach uses paired matching:

```json
{
  "bool": {
    "filter": [{
      "bool": {
        "should": [
          { "bool": { "must": [
            { "term": { "resource_attributes_context": "https://schema.org/Product" } },
            { "term": { "resource_attributes_type": "GroceryItem" } }
          ]}},
          { "bool": { "must": [
            { "term": { "resource_attributes_context": "https://beckn.org/Mobility" } },
            { "term": { "resource_attributes_type": "RideService" } }
          ]}}
        ],
        "minimum_should_match": 1
      }
    }]
  }
}
```

When a schemaContext URL has no fragment (just `https://schema.org`), only the context term is used (no type filter for that pair).

### 5 ES Query Sites Modified

| # | Site | Where filter goes |
|---|------|-------------------|
| 1 | **Keyword BM25** (`ElasticsearchTextSearchEngine`) | `bool { must: [multi_match], filter: [schema pairs] }` |
| 2 | **Semantic KNN** (`ElasticsearchTextSearchEngine`) | `knn { filter: [schema pairs] }` — restricts ANN candidate pool |
| 3 | **Spatial only** (`ElasticsearchQueryEngine`) | `bool { must: [geo], filter: [schema pairs] }` |
| 4 | **Spatial + text** (`ElasticsearchQueryEngine`) | `bool { must: [geo, multi_match], filter: [schema pairs] }` |
| 5 | **Spatial + semantic** (`ElasticsearchQueryEngine`) | `knn { filter: [geo, schema pairs] }` |

### KNN Filter Recommendation

Use `knn.filter` (not `post_filter`). Rationale:
- Restricts the ANN candidate pool to the right schema domain — semantically correct ("find nearest vectors within this schema")
- Matches the existing `geo_shape` filter pattern already in the codebase
- More efficient: KNN searches fewer candidates
- `post_filter` would waste KNN compute on irrelevant documents and potentially return zero results after filtering

### Pipeline Conditional Skip

```java
// TextSearchEngine.java — new default method
default boolean appliesSchemaFilter() { return false; }

// ElasticsearchTextSearchEngine — override
@Override public boolean appliesSchemaFilter() { return true; }

// NLWebTextSearchEngine — inherits false (no change needed)

// CatalogPipeline.java — new 3-arg overload
public List<Catalog> process(List<Catalog> catalogs, QueryRequest request, boolean schemaPreFiltered) {
    if (!schemaPreFiltered) step1FilterBySchemaContext(catalogs, request);
    step2(...);
    // ...
}
// Existing 2-arg delegates with false
public List<Catalog> process(List<Catalog> catalogs, QueryRequest request) {
    return process(catalogs, request, false);
}

// DiscoveryService — paths A/B/C pass true, path D uses engine flag
catalogPipeline.process(catalogs, qr, true);                          // paths A/B/C (PG+ES)
catalogPipeline.process(catalogs, qr, textSearchEngine.appliesSchemaFilter()); // path D
```

### Files to Create

| File | Description |
|------|-------------|
| `service/elasticsearch/EsSchemaFilterBuilder.java` | Stateless final utility. `static List<Query> buildSchemaFilters(QueryRequest)`. Builds paired (context, type) tuple filter queries. |
| `test/.../EsSchemaFilterBuilderTest.java` | Unit tests: empty, context-only, type-only, both, multiple pairs, no-fragment URLs |

### Files to Modify

| File | Change |
|------|--------|
| `ElasticsearchTextSearchEngine.java` | Add schema filters to BM25 `bool.filter` and KNN `knn.filter`. Override `appliesSchemaFilter() → true`. |
| `ElasticsearchQueryEngine.java` | Add schema filters to all 3 spatial query paths. |
| `TextSearchEngine.java` | Add `default boolean appliesSchemaFilter() { return false; }` |
| `CatalogPipeline.java` | Add 3-arg `process()` overload, conditional step1 skip. |
| `DiscoveryService.java` | Pass `schemaPreFiltered` flag to pipeline in all paths. |
| `ElasticsearchTextSearchEngineIntegrationTest.java` | Seed docs with different schema contexts, assert filtering works. |

### Key Method Signature

```java
public final class EsSchemaFilterBuilder {
    private EsSchemaFilterBuilder() {}

    static final String FIELD_CONTEXT = "resource_attributes_context";
    static final String FIELD_TYPE    = "resource_attributes_type";

    /**
     * Builds paired (context, type) ES filter queries from the request's
     * schemaContext URLs. Each URL is split into base URL (context) and
     * fragment (type). Returns a bool.should with one must-pair per URL.
     *
     * Returns empty list when no schema filtering is requested (no-op).
     * Handles unbounded cardinality (many URLs in a single terms clause).
     */
    public static List<Query> buildSchemaFilters(QueryRequest req) { ... }
}
```

---

## Design B: QueryRequest-Aware ES Query Wrapper + Split Pipeline (NOT RECOMMENDED)

**Score: 4.45 / 5.00**

### Core Idea

Introduce an `EsQueryWrapper` Spring `@Component` that wraps raw ES queries with schema context filters, and split `CatalogPipeline` into `FullCatalogPipeline` (NLWeb) and `SlimCatalogPipeline` (ES/PG — skips step1).

### Why Not Recommended

- Two pipeline classes violate DRY and risk divergence over time
- Spring `@Component` for stateless filter building is over-engineered
- Constructor changes in both engine classes and DiscoveryService
- Wrapper constrains how callers structure their queries
- More moving parts for no additional benefit

---

## Scoring Table

| Criterion | Weight | A | B |
|-----------|--------|---|---|
| Correctness | 20% | 5 | 5 |
| Performance | 20% | 5 | 5 |
| Security | 15% | 5 | 5 |
| Maintainability | 15% | 5 | 3 |
| Beckn V2 compliance | 15% | 5 | 5 |
| Testability | 10% | 5 | 4 |
| Simplicity | 5% | 5 | 2 |
| **Weighted** | | **5.00** | **4.45** |

---

## What NOT to Do

- Do NOT make `EsSchemaFilterBuilder` a Spring `@Component` — stateless utility with static methods
- Do NOT use `post_filter` for KNN — use `knn.filter`
- Do NOT modify PostgreSQL query path — schema filtering already works in SQL
- Do NOT modify `NLWebTextSearchEngine` — it relies on pipeline post-filter via `false` default
- Do NOT add new config properties — ES index already has required keyword fields
- Do NOT change `QueryRequest` — it already carries `schemaContextUrls()` and `schemaTypes()`
- Do NOT change ES index mapping — `resource_attributes_context` and `resource_attributes_type` already exist
- Do NOT put schema filters in `must` clause — use `filter` only (no scoring impact)
- Do NOT remove `CatalogProcessor.filterCatalogsBySchemaContext()` — still used for NLWeb
- Do NOT create separate pipeline subclass — boolean parameter is sufficient
- Do NOT use `instanceof` in DiscoveryService — use `appliesSchemaFilter()` interface method
- Do NOT use independent `terms` filters on context and type separately — use paired tuple matching to prevent cross-matches

## Acceptance Criteria

- [ ] `EsSchemaFilterBuilder.buildSchemaFilters(req)` returns empty list when both `schemaContextUrls` and `schemaTypes` are empty
- [ ] Returns paired (context+type) bool.should query for each schemaContext URL
- [ ] Handles URLs without fragments (context-only filter)
- [ ] Handles unbounded cardinality
- [ ] Keyword BM25 applies paired schema filters as `bool.filter`
- [ ] Semantic KNN applies paired schema filters as `knn.filter`
- [ ] All 3 spatial paths apply paired schema filters correctly
- [ ] No-schemaContext requests produce unchanged ES queries (empty filter list)
- [ ] `CatalogPipeline.process(catalogs, req, true)` skips step1
- [ ] `CatalogPipeline.process(catalogs, req, false)` runs step1 (NLWeb)
- [ ] `CatalogPipeline.process(catalogs, req)` backward-compat defaults to `false`
- [ ] `ElasticsearchTextSearchEngine.appliesSchemaFilter()` returns `true`
- [ ] `NLWebTextSearchEngine` inherits `false`
- [ ] Integration test verifies schema-filtered search excludes non-matching documents
- [ ] Integration test verifies no cross-matching between different schema pairs
- [ ] Integration test verifies no-schema request returns all docs (regression guard)
- [ ] All existing tests pass without modification
