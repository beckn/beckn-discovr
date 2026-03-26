# Beckn DISCOVR Service

Welcome to the documentation for Beckn DISCOVR — a high-performance, real-time discovery engine that enables consumers to find resources across all published catalogues in Beckn-enabled networks.

This guide is intended for BAP developers, consumer application builders, network facilitators, and anyone building search experiences on top of Beckn catalogues.

## What is Beckn DISCOVR?

Beckn DISCOVR is the query-time search and discovery layer for Beckn ecosystems.

While Beckn CATALG acts as the source of truth for catalogue publishing and validation, DISCOVR is purpose-built for **real-time search** — it indexes catalogue data from CATALG and exposes it through fast, flexible query APIs that power consumer-facing search experiences.

DISCOVR receives catalogue updates from CATALG (via subscription-based delivery), indexes them for search, and serves discovery requests from BAPs and consumer applications with sub-second response times.

## Why Do You Need Beckn DISCOVR?

Without a dedicated discovery layer, consumer applications face:

- Querying raw catalogue data directly — slow, unstructured, and unscalable
- Building custom search indexes per application — duplicated effort, inconsistent results
- No support for natural language, spatial, or attribute-based search across providers
- No unified view of resources across multiple catalogues and networks

Beckn DISCOVR solves this by offering:

- **One search endpoint** across all catalogues published to the network
- **Multiple search modes** — natural language, keyword, spatial (location-based), and JSONPath attribute filtering
- **Real-time indexing** — catalogue changes from CATALG are indexed and searchable within seconds
- **Provider-agnostic results** — consumers see resources from all providers, with offers from multiple retailers in one response

## Key Capabilities

### For Consumer Applications (BAPs)

- **Natural language search** — ask questions like "strong Assam tea for morning chai" or "premium coffee under 500 rupees"
- **Keyword search** — find resources by name, description, brand, or any indexed attribute
- **Spatial search** — find resources available near a location using GeoJSON coordinates and distance radius
- **Attribute filtering** — query resources and offers using JSONPath expressions for fine-grained filtering (e.g., "flat discount offers under 100")
- **Combined queries** — mix text search with spatial constraints in a single request
- **Schema-aware results** — responses include full resource attributes, ratings, offers, and provider details

### For Discovery Service Builders

- **Subscription-driven indexing** — DISCOVR subscribes to CATALG and receives catalogue updates automatically via `on_discover` callbacks
- **Full-text and spatial search** — supports keyword matching, natural language understanding, and geospatial proximity queries
- **Pipeline processing** — incoming catalogues pass through schema filtering, deduplication, and pruning before being served to consumers

### For Network Facilitators

- **Network-wide search** — query across all providers and catalogues in a network
- **Schema type filtering** — discover resources by domain type (e.g., GroceryItem, ChargingService, HealthcareProvider)
- **Offer comparison** — see multiple retailer offers for the same resource in one response
- **Real-time availability** — search results reflect the latest published catalogue state

## High-Level Architecture

```
Beckn CATALG                           Beckn DISCOVR
(source of truth)                      (search engine)
      |                                      |
      |  on_discover callback                |
      |  (catalogue updates delivered        |
      |   to subscribed DISCOVR instances)   |
      |------------------------------------->|
      |                                      |  Index for search
      |                                      |  Store for spatial queries
      |                                      |
      |                                      |
Consumer (BAP)                               |
      |                                      |
      |  GET /beckn/discover                 |
      |  (text / spatial / JSONPath)         |
      |------------------------------------->|
      |                                      |  Query Elasticsearch + PostgreSQL
      |  on_discover response                |
      |  (matching resources + offers)       |
      |<-------------------------------------|
```

## Example Use Case

A consumer opens a grocery shopping app and searches for "instant coffee near me". The BAP sends a discover request to Beckn DISCOVR combining natural language text search with a spatial constraint (5 km radius around the consumer's location).

DISCOVR queries its search index for resources matching "instant coffee" and its spatial index for resources with availability locations within 5 km. The response includes Bru Gold Coffee, Nescafe Classic, and Continental Xtra — each with offers from multiple retailers (Amazon, Flipkart, local stores) showing different prices, discounts, and delivery estimates.

The consumer compares offers and proceeds to order from the retailer with the best price and fastest delivery.

## Design Principles

- **Real-time query engine**: Optimized for sub-second search — not for catalogue storage or validation (that's CATALG's role)
- **Subscription-driven**: Receives catalogue updates from CATALG automatically — no polling or manual sync
- **Multi-modal search**: Supports text, natural language, spatial, and structured attribute queries — individually or combined
- **Provider-agnostic**: Returns resources from all providers in a network, with competing offers side by side
- **Schema-aware**: Understands Beckn resource and offer schemas — filters, groups, and prunes results based on schema context

## API Reference

DISCOVR exposes a single, powerful discovery endpoint that supports multiple search modes through the standard Beckn `discover` / `on_discover` flow.

### Discovery APIs (Consumer-facing)

**`GET /beckn/discover`** — Synchronous discovery. BAP sends a discover request with intent (text, spatial, or filter), DISCOVR returns matching catalogues with resources and offers immediately.

**`POST /beckn/discover`** — Asynchronous discovery. DISCOVR acknowledges with `ACK` and delivers results via callback to `{bapUri}/on_discover`.

### Search Modes

| Mode | Intent Field | Description | Example |
|------|-------------|-------------|---------|
| **Text / Natural Language** | `textSearch` | Keyword or conversational query | `"strong Assam tea for chai"` |
| **Spatial** | `spatial` | Find resources near a location | `s_dwithin` with coordinates + radius |
| **JSONPath Filter** | `filters` | Attribute-based filtering | `$.offers[*] ? (@.price < 100)` |
| **Combined** | Multiple fields | Mix text + spatial + filters | Text search within 5 km radius |

### Request Format

```json
{
  "context": {
    "version": "2.0.0",
    "action": "discover",
    "messageId": "<uuid>",
    "transactionId": "<uuid>",
    "timestamp": "<ISO 8601>",
    "bapId": "<your-identifier>",
    "bapUri": "<your-callback-url>",
    "networkId": "<network-id>",
    "schemaContext": []
  },
  "message": {
    "intent": {
      "textSearch": "instant coffee",
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

### Response Format

```json
{
  "context": {
    "action": "on_discover",
    "messageId": "...",
    "transactionId": "..."
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
            "id": "OFFER-AMAZON-BRU",
            "resourceIds": ["ITEM-BRU-COFFEE"],
            "offerAttributes": {
              "priceSpecification": { "price": 170, "discount": "15%" }
            }
          }
        ]
      }
    ]
  }
}
```

### How DISCOVR Stays Updated

DISCOVR does not poll CATALG for changes. Instead, it uses Beckn's subscription mechanism:

1. DISCOVR subscribes to CATALG using `POST /beckn/catalog/subscription` with network and schema type filters
2. When a provider publishes or updates a catalogue in CATALG, the evaluator matches active subscriptions
3. Matching catalogue data is delivered to DISCOVR via `POST {discovrUri}/on_discover` callback
4. DISCOVR indexes the received catalogue for search and spatial queries
5. The updated data is immediately searchable via `GET /beckn/discover`

For historical data or initial sync, DISCOVR uses the CATALG Pull API (`POST /catalog/pull`) to fetch all existing catalogues.
