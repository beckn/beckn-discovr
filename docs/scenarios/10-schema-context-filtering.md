# 10 — Schema Context Filtering (ES Pushdown)

## Overview
Verify that `schemaContext` filtering is pushed down into Elasticsearch queries (BM25, KNN, spatial) so only matching documents are returned from ES, eliminating wasteful post-filter discard. Covers paired tuple matching, fragment-only URLs, combined filters, and metrics.

## Prerequisites
- Catalog from 01-catalog-ingestion SC-01 must be indexed (resources with `@context: "https://schema.org"`, `@type: "GroceryItem"`)
- Additional resources with a DIFFERENT schema context must be pushed (see Test Data below)

## Test Data

Push a second catalog with resources using a different schema context to enable cross-match verification:

```json
{
  "context": { "...standard fields..." },
  "message": {
    "catalogs": [{
      "id": "DSC-SCHEMA-<TS>",
      "descriptor": { "name": "Schema Filter Test Catalog" },
      "bppId": "<BPP_ID>",
      "bppUri": "https://<BPP_ID>",
      "resources": [
        {
          "id": "RS1-<TS>",
          "descriptor": { "name": "Schema Ride Service", "shortDesc": "Test mobility resource" },
          "availableAt": [{
            "geo": { "type": "Point", "coordinates": [77.5946, 12.9716] },
            "address": { "streetAddress": "MG Road", "addressLocality": "Bengaluru" }
          }],
          "resourceAttributes": { "@context": "https://beckn.org/Mobility", "@type": "RideService", "vehicleType": "sedan" }
        },
        {
          "id": "RS2-<TS>",
          "descriptor": { "name": "Schema Bike Service", "shortDesc": "Test bike resource" },
          "resourceAttributes": { "@context": "https://beckn.org/Mobility", "@type": "BikeService", "vehicleType": "bike" }
        },
        {
          "id": "RS3-<TS>",
          "descriptor": { "name": "Schema Generic Product", "shortDesc": "Context-only match test" },
          "resourceAttributes": { "@context": "https://schema.org", "@type": "ElectronicsItem", "brand": "TestBrand" }
        }
      ]
    }]
  }
}
```

## Scenarios

| # | Scenario | Method | Expected |
|---|----------|--------|----------|
| SC-44 | Single schemaContext — only matching docs | GET discover with `schemaContext: ["https://schema.org/Product#GroceryItem"]` | `message.catalogs` contains only resources where `@context = "https://schema.org/Product"` AND `@type = "GroceryItem"`. No Mobility resources returned. |
| SC-45 | Multiple schemas, different base URLs — paired matching | GET discover with `schemaContext: ["https://schema.org#GroceryItem", "https://beckn.org/Mobility#RideService"]` | Results include GroceryItem resources AND RideService resources. No cross-matches (e.g., no `@context: "https://schema.org"` + `@type: "RideService"`). |
| SC-46 | Fragment-only — same base URL, different type | GET discover with `schemaContext: ["https://schema.org#ElectronicsItem"]` | Returns RS3 (`@type: "ElectronicsItem"`) but NOT GroceryItem resources despite same `@context` base. Paired tuple matching enforced. |
| SC-47 | No fragment — context-only filter | GET discover with `schemaContext: ["https://beckn.org/Mobility"]` | Returns ALL resources with `@context = "https://beckn.org/Mobility"` regardless of `@type` (RS1 + RS2). No schema.org resources returned. |
| SC-48 | Empty schemaContext — no filter applied | GET discover with `textSearch: "Schema"` and no `schemaContext` | All matching resources returned regardless of `@context`/`@type`. Regression guard: no accidental empty-filter exclusion. |
| SC-49 | Combined: schemaContext + textSearch | GET discover with `schemaContext: ["https://beckn.org/Mobility#RideService"]` AND `textSearch: "Schema Ride"` | Only resources matching BOTH the schema pair AND the text query. Returns RS1, excludes RS2 (wrong type) and GroceryItems (wrong context). |
| SC-50 | Combined: schemaContext + spatial | GET discover with `schemaContext: ["https://beckn.org/Mobility#RideService"]` AND spatial near [77.5946, 12.9716] distanceMeters 5000 | Only resources matching BOTH the schema pair AND the spatial proximity. Returns RS1 (has location + correct schema), excludes RS2 (no location). |
| SC-51 | No matches — schemaContext that matches nothing | GET discover with `schemaContext: ["https://example.org/NonExistent#FakeType"]` | `message.catalogs` = empty array. No errors, clean empty response. |
| SC-52 | KNN path — semantic search + schemaContext | GET discover with semantic/KNN search + `schemaContext: ["https://beckn.org/Mobility#RideService"]` | KNN candidate pool restricted to Mobility/RideService docs only. Results contain only matching schema resources. |
| SC-53 | Metrics — schema filter counter | `curl -s http://localhost:8082/actuator/prometheus` after SC-44 through SC-52 | `discovr_discover_schema_filter_applied` counter > 0 (incremented for each request with non-empty schemaContext) |

## Verification Depth

### For every schemaContext discover response:
1. Parse full JSON, check `context.action = "on_discover"`
2. `message.catalogs` is array (empty for no-match scenarios)
3. Every returned resource's `resourceAttributes.@context` + `resourceAttributes.@type` matches at least one requested schema pair
4. No cross-matches: if request has two schema pairs with different base URLs, no resource matches context from pair A with type from pair B

### Paired tuple correctness:
- `["https://schema.org#GroceryItem", "https://beckn.org/Mobility#RideService"]` must NOT match a resource with `@context: "https://schema.org"` + `@type: "RideService"`
- Fragment-only URLs must pair correctly: `https://schema.org#ElectronicsItem` matches `@context: "https://schema.org"` + `@type: "ElectronicsItem"`, NOT `@type: "GroceryItem"`

### Pipeline skip verification:
- For ES-backed paths (keyword, KNN, spatial), `CatalogPipeline.step1FilterBySchemaContext` should be skipped (schema filtering already done in ES query)
- Verify via logs or metrics that the pipeline received pre-filtered results (no post-filter discard logged)

### ES query verification (optional, for deep debugging):
```bash
# Enable slow query log or check via _search with explain=true
curl -s "http://localhost:9200/beckn-catalog-*/_search?explain=true" -H 'Content-Type: application/json' -d '{
  "query": { "bool": { "filter": [{ "bool": { "should": [
    { "bool": { "must": [
      { "term": { "resource_attributes_context": "https://schema.org" }},
      { "term": { "resource_attributes_type": "GroceryItem" }}
    ]}}
  ]}}]}}
}'
```
Assert: only GroceryItem docs in hits.
