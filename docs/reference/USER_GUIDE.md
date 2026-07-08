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

**`GET /beckn/discover`**

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

Query resources and offers using JSONPath expressions for fine-grained attribute filtering.

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
        "expression": "$.catalogs[*].offers[*] ? (@.offerAttributes.tariffModel == 'FLAT_DISCOUNT' && @.offerAttributes.priceSpecification.price < 100)"
      }
    }
  }
}
```

This finds all offers with a flat discount where the price is under 100.

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

| Operation | Method | Endpoint | Action |
|-----------|--------|----------|--------|
| **Discover resources** | GET | `/beckn/discover` | `discover` |

