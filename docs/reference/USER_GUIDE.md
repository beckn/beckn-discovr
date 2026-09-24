# Beckn Discovery Service — API Guide

How to discover resources across published catalogs using the Beckn Protocol v2.0.

---

## How It Works

The Discovery Service searches across all catalogs published to the Catalog Service. It supports natural language search, keyword search, location-based search, and attribute filtering.

```
Consumer (BAP)                              Discovery Service
     |                                           |
     |  Discover request                         |
     |  (text / spatial / JSONPath)              |
     |------------------------------------------>|
     |                                           |  Searches indexed catalogs
     |       on_discover response                |
     |  <----------------------------------------|
     |  (matching catalogs, resources, offers)   |
```

Discovery searches the **full indexed catalog** — it returns results regardless of when the data was published. No subscription is needed to use the discover API.

---

## Request Format

```json
{
  "context": {
    "version": "2.0.0",
    "action": "discover",
    "messageId": "<uuid>",
    "transactionId": "<uuid>",
    "timestamp": "<ISO 8601>",
    "bapId": "<your-identifier>",
    "bapUri": "<your-callback-url>"
  },
  "message": {
    "intent": { ... }
  }
}
```

---

## 1. Text Search

Search using keywords or natural language queries. The discovery service supports both simple keyword matching and natural language understanding — you can search the way your users would ask.

**`GET /discover`** (synchronous, additional — for fast local integration only, not for production; see [API Reference](#api-reference)) or **`POST /discover`** (asynchronous, Beckn-spec-compliant)

### Keyword Search

```json
{
  "context": {
    "version": "2.0.0",
    "action": "discover",
    "messageId": "55555555-5555-5555-5555-555555555555",
    "transactionId": "66666666-6666-6666-6666-666666666666",
    "timestamp": "2026-03-26T11:00:00Z",
    "bapId": "bap.myapp.in",
    "bapUri": "https://bap.myapp.in",
    "networkId": "retail-grocery",
    "schemaContext": []
  },
  "message": {
    "intent": {
      "textSearch": "Coffee"
    }
  }
}
```

### Natural Language Search

You can also use natural language queries — the service understands conversational phrasing and extracts the relevant search intent:

```json
{
  "message": {
    "intent": {
      "textSearch": "strong Assam Darjeeling tea for morning chai"
    }
  }
}
```

```json
{
  "message": {
    "intent": {
      "textSearch": "premium instant coffee under 500 rupees"
    }
  }
}
```

The service matches against resource names, descriptions, and domain-specific attributes to find the most relevant results.

> **Operator note:** natural-language understanding backed by semantic search (`discovery.text-search.engine=els-semantic-search`) calls out to an OpenAI-compatible embedding model and, optionally, an LLM for query enrichment — both may require an API key depending on the provider. See the [Semantic search API keys](../../jobs/catalog-discover-job/README.md#configuration-high-level) config note.

---

## 2. Spatial Search

Find resources available near a location. Uses spatial queries with CQL2-JSON semantics.

```json
{
  "context": {
    "version": "2.0.0",
    "action": "discover",
    "messageId": "bb9f86db-9a3d-4e9c-8c11-81c8f1a7b901",
    "transactionId": "f9d1e7f3-5f1a-4d23-9f10-31b72c0b0c01",
    "timestamp": "2026-03-26T12:00:00Z",
    "bapId": "bap.myapp.in",
    "bapUri": "https://bap.myapp.in",
    "networkId": "retail-grocery"
  },
  "message": {
    "intent": {
      "spatial": [
        {
          "op": "s_dwithin",
          "targets": "$.catalogs[*].resources[*].availableAt[*].geo",
          "geometry": {
            "type": "Point",
            "coordinates": [76.6527, 12.3116]
          },
          "distanceMeters": 1000
        }
      ]
    }
  }
}
```

This finds all resources available within 1 km of the specified coordinates (Mysore in this example).

| Field | Description |
|-------|-------------|
| `op` | Spatial operation — `s_dwithin` (within distance) |
| `targets` | JSONPath to the geo field in the resource |
| `geometry` | GeoJSON point with `[longitude, latitude]` |
| `distanceMeters` | Search radius in meters |

---

## 3. JSONPath Filter

Query resources and offers using JSONPath expressions for fine-grained attribute filtering. Write filters in **RFC 9535** syntax — the standard JSONPath filter-selector grammar:

```json
{
  "context": {
    "version": "2.0.0",
    "action": "discover",
    "messageId": "bb9f86db-9a3d-4e9c-8c11-81c8f1a7b901",
    "transactionId": "f9d1e7f3-5f1a-4d23-9f10-31b72c0b0c01",
    "timestamp": "2026-03-26T12:00:00Z",
    "bapId": "bap.myapp.in",
    "bapUri": "https://bap.myapp.in",
    "networkId": "retail-grocery"
  },
  "message": {
    "intent": {
      "filters": {
        "type": "jsonpath",
        "expression": "$.catalogs[*].offers[?(@.offerAttributes.tariffModel == \"FLAT_DISCOUNT\" && @.offerAttributes.priceSpecification.price < 100)]"
      }
    }
  }
}
```

This finds all offers with a flat discount where the price is under 100. Note the RFC 9535 filter selector `[?( ... )]` sits inside brackets right after the segment it filters (`offers[?(...)]`), and string literals are double-quoted.

> **Migrating from the legacy PostgreSQL jsonpath dialect?** Older integrations may still send expressions in Postgres's own `jsonpath` dialect (predicate trailing the path as `? (...)`, single-quoted strings, e.g. `$.catalogs[*].offers[*] ? (@.price < 100)`). The engine auto-detects and still accepts this legacy form as a fallback, but it is not the standard and will not be extended — new integrations should use RFC 9535 syntax as shown above. Some newer RFC 9535 constructs are not supported (e.g. `..` descendant segments, `[a:b:s]` slices with a step, or the `count()`/`value()`/`match()`/`search()` functions) — see [DESIGN-rfc9535-jsonpath-grammar.md](../design/DESIGN-rfc9535-jsonpath-grammar.md) for the full grammar and denylist.

---

## 4. Combining Search Modes

You can combine text search with spatial or filter queries in a single request:

```json
{
  "message": {
    "intent": {
      "textSearch": "Coffee",
      "spatial": [
        {
          "op": "s_dwithin",
          "targets": "$.catalogs[*].resources[*].availableAt[*].geo",
          "geometry": { "type": "Point", "coordinates": [77.6401, 12.9116] },
          "distanceMeters": 5000
        }
      ]
    }
  }
}
```

This finds coffee-related resources available within 5 km of Bengaluru HSR Layout.

---

## Response Format

All discover responses follow this structure:

```json
{
  "context": {
    "action": "on_discover",
    "messageId": "...",
    "transactionId": "...",
    "bapId": "...",
    "timestamp": "..."
  },
  "message": {
    "catalogs": [
      {
        "id": "CAT-GROCERY-001",
        "descriptor": { "name": "FreshMart Grocery Catalog" },
        "resources": [
          {
            "id": "ITEM-BRU-COFFEE",
            "descriptor": { "name": "Bru Gold Instant Coffee" },
            "rating": { "ratingValue": 4.1, "ratingCount": 18200 },
            "resourceAttributes": { "@type": "GroceryItem", "brand": "Bru" }
          }
        ],
        "offers": [
          {
            "id": "OFFER-COFFEE-BUNDLE",
            "descriptor": { "name": "Coffee Lovers Bundle" },
            "resourceIds": ["ITEM-BRU-COFFEE", "ITEM-NESCAFE"],
            "offerAttributes": {
              "priceSpecification": { "price": 255, "discount": "15%" }
            }
          }
        ]
      }
    ]
  }
}
```

The response includes only catalogs, resources, and offers that match your query. Empty results return `"catalogs": []`.

---

## Error Responses

```json
{
  "status": "NACK",
  "error": {
    "errorCode": "SCH_REQUIRED_FIELD_MISSING",
    "errorMessage": "context: must have required property 'transactionId'"
  }
}
```

| Error Code | Meaning |
|------------|---------|
| `SCH_REQUIRED_FIELD_MISSING` | A required field is missing |
| `SCH_SCHEMA_VALIDATION_FAILED` | Request doesn't match the expected schema |
| `CTX_INVALID_FIELD` | Invalid field format (e.g., non-UUID transactionId) |

---

## API Reference

| Operation | Method | Endpoint | Action | Beckn spec status |
|-----------|--------|----------|--------|--------------------|
| Discover resources (synchronous) | GET | `/discover` | `discover` | **Additional, non-production** — see note below |
| Discover resources (asynchronous) | POST | `/discover` | `discover` | **Beckn-spec-compliant** — returns ACK immediately, delivers results via `on_discover` callback to `bapUri` |
| Catalog ingestion push | POST | `/catalog/push` (catalog-publish-job) | — | **Additional, actively used** — not a `beckn.yaml` action; still the live path for the crawler plugin to push catalog files |
| Catalog pull callback | POST | `/catalog/on_pull` (catalog-publish-job) | `catalog/on_pull` | **Beckn-spec-compliant, unused** — see note below |
| Admin stats reset | POST | `/discovery-service/health/reset-stats` (catalog-discover-job) | — | **Additional** — operational/admin endpoint, not part of the Beckn spec |

Health and metrics are served by Spring Boot Actuator (`/actuator/health`, `/actuator/metrics/*`, `/actuator/prometheus`) on each job — also not Beckn-spec endpoints.

> **`GET /discover` — do not use in production.** This synchronous mode exists purely to let a new integrator get a result back in the same request/response round trip while wiring up their app, without first standing up a callback receiver for `on_discover`. Once an app is ready for production traffic it should move to `POST /discover` (the Beckn-spec-compliant async flow) — the GET path is a convenience for early integration, not a supported production API.

> **`POST /catalog/on_pull` is likely to be removed.** It implements the spec's `catalog/on_pull` callback correctly, but nothing in the current pipeline issues a `catalog/pull` request that would trigger it, so it sees no live traffic. Catalog ingestion today happens exclusively via `POST /catalog/push` (used by the crawler plugin). Treat `on_pull` as deprecated rather than building new integrations against it.

