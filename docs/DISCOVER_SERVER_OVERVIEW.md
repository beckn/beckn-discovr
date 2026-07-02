# Beckn Discovr — Discover Server Overview

## Document Control

| Field | Value |
|---|---|
| Document Title | Discover Server Overview |
| Version | 1.1 |
| Date | 2026-07-02 |
| Status | Final |
| Audience | External Partners & Integrators |

## Revision History

| Version | Date | Description |
|---|---|---|
| 1.0 | 2026-07-02 | Initial version |
| 1.1 | 2026-07-02 | Standardized document structure; added interaction diagram and glossary; corrected API examples |

## Table of Contents

1. [Introduction](#1-introduction)
2. [Background](#2-background)
3. [Service Overview](#3-service-overview)
4. [Capabilities](#4-capabilities)
5. [Interaction Flow](#5-interaction-flow)
6. [Integration Expectations](#6-integration-expectations)
7. [API Contract](#7-api-contract)
8. [Glossary](#8-glossary)
9. [Summary](#9-summary)
10. [Next Steps](#10-next-steps)

## 1. Introduction

This document provides a high-level overview of the Discover server, a
component of Beckn Discovr. It is intended to give partners and integrators
a clear picture of what the Discover server does and what to expect when
interacting with it. It does not cover internal system design or
implementation details.

## 2. Background

The Beckn network is built on an open protocol that lets independent Buyer
Apps (BAPs) and Provider Platforms (BPPs) interact through a common set of
standards. Beckn Discovr operates within this network and provides catalog
discovery — enabling Buyer Apps to search for items or services published by
providers on the network.

## 3. Service Overview

The Discover server is the search component of Beckn Discovr. It allows
Buyer Apps to search the catalog and receive matching results.

## 4. Capabilities

- **Attribute-based search** — filter by category, price range, or other
  item/service properties
- **Location-based search** — find items or providers near a given location
  or within a defined area
- **Free-text search** — natural-language style queries
- **Combined search** — any of the above used together in a single request

## 5. Interaction Flow

At a high level, a Buyer App and the Discover server interact as follows:

```
 Buyer App (BAP)                              Discover Server
        │                                             │
        │──────────── discover (request) ───────────>│
        │                                             │
        │<─────────────── ACK / NACK ─────────────────│
        │                                             │
        │                                        (search catalog)
        │                                             │
        │<───────────── on_discover (callback) ───────│
        │                                             │
```

1. The Buyer App sends a `discover` request describing what it's looking for.
2. The Discover server immediately acknowledges (`ACK`) or rejects (`NACK`)
   the request.
3. If accepted, the Discover server searches the catalog and returns matching
   results to the Buyer App via an `on_discover` callback.

## 6. Integration Expectations

- Standard Beckn protocol interface (`discover` / `on_discover`) for network
  interoperability
- Results reflect the latest published catalog data
- Consistent behavior aligned with Beckn Protocol v2.0

## 7. API Contract

The Discover server exposes two standard Beckn protocol actions. A Buyer App
sends a search request via the `discover` action and receives matching
results asynchronously via the `on_discover` callback.

### 7.1 `discover` — Request

**Endpoint:** `POST /discover`

```json
{
  "context": {
    "action": "discover",
    "bapId": "buyer-app.example.com",
    "bapUri": "https://buyer-app.example.com",
    "networkId": "example-network",
    "messageId": "b0c204c2-1e1e-4c1a-9a1e-000000000001",
    "transactionId": "b0c204c2-1e1e-4c1a-9a1e-000000000000",
    "timestamp": "2026-07-02T10:00:00Z",
    "version": "2.0.0"
  },
  "message": {
    "intent": {
      "schemaContext": ["https://example.org/schema/retail"],
      "textSearch": "wireless headphones",
      "filters": {
        "type": "jsonpath",
        "expression": "$[?(@.category == 'electronics' && @.rating.value >= 4.0)]"
      },
      "spatial": [
        {
          "op": "s_dwithin",
          "targets": "$['availableAt'][*]['geo']",
          "geometry": { "type": "Point", "coordinates": [77.5946, 12.9716] },
          "distanceMeters": 5000
        }
      ]
    }
  }
}
```

A request may include any one of `textSearch`, `filters`, and `spatial`, or a
combination of them, depending on the search being performed.

### 7.2 `discover` — Acknowledgement

The server responds immediately to confirm the request was accepted; actual
results follow separately via the callback described in Section 7.3.

```json
{
  "message": {
    "status": "ACK",
    "messageId": "b0c204c2-1e1e-4c1a-9a1e-000000000001"
  }
}
```

If a request cannot be accepted, the server returns a NACK with an error
reason instead:

```json
{
  "message": {
    "status": "NACK",
    "messageId": "b0c204c2-1e1e-4c1a-9a1e-000000000001",
    "error": {
      "code": "SCH_SCHEMA_VALIDATION_FAILED",
      "message": "Request does not conform to the expected schema."
    }
  }
}
```

| HTTP Status | Meaning |
|---|---|
| 200 | Accepted — results will follow via `on_discover` |
| 202 | Accepted — no `on_discover` callback will follow |
| 400 | Bad request — malformed or invalid input |
| 401 / 403 | Authorization failure |
| 429 | Too many requests |
| 500 | Server error |

### 7.3 `on_discover` — Callback

Once results are ready, the Discover server delivers them to the Buyer App's
callback endpoint. The callback context carries the **same `messageId`** as
the original request, which the Buyer App uses to correlate the response.

**Endpoint:** `POST {bapUri}/on_discover`

```json
{
  "context": {
    "action": "on_discover",
    "bapId": "buyer-app.example.com",
    "bapUri": "https://buyer-app.example.com",
    "networkId": "example-network",
    "messageId": "b0c204c2-1e1e-4c1a-9a1e-000000000001",
    "transactionId": "b0c204c2-1e1e-4c1a-9a1e-000000000000",
    "timestamp": "2026-07-02T10:00:05Z",
    "version": "2.0.0"
  },
  "message": {
    "catalogs": [
      {
        "id": "catalog-001",
        "descriptor": { "name": "Example Provider Catalog" },
        "resources": [
          {
            "id": "resource-001",
            "descriptor": { "name": "Wireless Headphones" }
          }
        ],
        "offers": [
          {
            "id": "offer-001",
            "descriptor": { "name": "10% off" },
            "resourceIds": ["resource-001"]
          }
        ]
      }
    ]
  }
}
```

## 8. Glossary

| Term | Definition |
|---|---|
| BAP | Buyer App — the consumer-facing application that initiates a `discover` request on behalf of a user |
| BPP | Provider Platform — a seller/service-provider platform that publishes catalog data to the network |
| ACK | Acknowledgement — confirms a request was received and accepted for processing |
| NACK | Negative Acknowledgement — indicates a request was rejected, with an error code and message |
| Catalog | A published collection of resources (items/services) and offers available for discovery |
| Resource | An individual item or service within a catalog |
| Offer | A commercial term (e.g., discount, bundle) associated with one or more resources |
| messageId | A unique identifier correlating a request with its corresponding callback |

## 9. Summary

The Discover server gives Buyer Apps a reliable, standards-compliant way to
search catalog data on the Beckn network, supporting attribute-based,
location-based, text-based, and combined search modes through a simple
request/callback contract.

## 10. Next Steps

For a technical walkthrough or further clarification on the API contract
described in Section 7, please reach out to the Beckn Discovr team.
