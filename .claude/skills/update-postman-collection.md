---
name: update-postman-collection
description: Updates the Discovr Postman collection to match the current beckn.yaml schema and API implementation. Reviews every endpoint, fixes request bodies, headers, URLs, and adds missing endpoints.
user_invocable: true
---

## When to use

When the user says: "update postman", "sync postman collection", "postman needs updating", "update the collection", `/update-postman-collection`.

## Inputs

- **Schema file**: The Beckn Protocol OpenAPI spec (beckn.yaml). User provides the path or it defaults to the last-known location.
- **Postman collection**: `api-collection/Beckn-2.0-Discovr.postman_collection.json`

## Workflow

### Step 1 — Discover all API endpoints

Read these source files to get the authoritative list of endpoints:

**Catalog Discover Job:**
- `jobs/catalog-discover-job/src/main/java/org/beckn/discover/controller/DiscoveryController.java` — GET + POST /beckn/discover
- `jobs/catalog-discover-job/src/main/java/org/beckn/discover/service/DiscoveryService.java` — orchestration
- `jobs/catalog-discover-job/src/main/java/org/beckn/discover/service/validation/DiscoveryValidationService.java` — validation rules
- `jobs/catalog-discover-job/src/main/java/org/beckn/discover/model/` — domain models (Context, Catalog, Resource, etc.)

**Catalog Publish Job:**
- `jobs/catalog-publish-job/src/main/java/org/beckn/catalogpublish/controller/CatalogPushController.java` — POST /catalog/push

**Response Dispatcher:**
- `jobs/response-dispatcher/` — internal only (no REST endpoints), but handles on_discover callbacks

### Step 2 — Read the beckn.yaml schema

Read the beckn.yaml file provided by the user. Extract for each endpoint:
- HTTP method and path
- Request body schema (all fields, types, required vs optional, enums, defaults)
- Response schema (ACK/NACK format, on_discover response envelope)
- Context field requirements (action const, version const)
- Intent structure (textSearch, filters, spatial)

### Step 3 — Read the current Postman collection

Read `api-collection/Beckn-2.0-Discovr.postman_collection.json`. For each request, note:
- Endpoint name, method, URL
- Request body JSON
- Headers
- Pre-request scripts
- Variables

### Step 4 — Diff and identify gaps

Compare each Postman request against beckn.yaml + source code. Flag:

1. **Missing endpoints** — API exists in code but not in collection
2. **Wrong HTTP method** — e.g., only GET when POST also exists
3. **Stale context fields** — removed/renamed fields (e.g., `bapId` if removed from system)
4. **Wrong request body structure** — fields not matching schema
5. **Missing headers** — `Authorization`, `Content-Type`, `X-Tags`
6. **Missing response examples** — add example responses for each endpoint
7. **Missing pre-request scripts** — signing script should apply to all endpoints with bodies
8. **Missing variables** — e.g., `publish-host` for catalog push

### Step 5 — Generate updated collection

Produce the updated Postman collection JSON with:

**Structure (folders):**
```
Beckn-2.0-Discovr
├── Discovery Service
│   ├── Discover - Text Search (GET, sync)
│   ├── Discover - Spatial Query Catalog (GET, sync)
│   ├── Discover - Spatial Query Offer (GET, sync)
│   ├── Discover - JSONPath Filter (GET, sync)
│   ├── Discover - Combined Search (GET, sync)
│   ├── Discover - Async (POST, returns ACK)
│   └── Discover - Async with Spatial (POST, returns ACK)
├── Catalog Push
│   └── Push Catalog (POST /catalog/push)
└── Health
    ├── Health Check (GET /actuator/health)
    └── Reset Stats (POST /discovery-service/health/reset-stats)
```

**For each request, ensure:**
- URL matches actual route
- Context fields match beckn.yaml Context schema exactly
- Action const matches the endpoint (`discover` for discovery, `catalog/push` for push)
- Version is "2.0.0"
- Request body matches beckn.yaml request schema
- Example response bodies are included
- Descriptions explain the endpoint behavior

**Variables:**
- `{{discover-host}}` — Catalog Discover Job (default: `http://localhost:8082`)
- `{{publish-host}}` — Catalog Publish Job (default: `http://localhost:8085`)
- `{{sign-host}}` — Signing service (default: `http://localhost:3032`)

### Step 6 — Write the updated collection

Write the updated JSON to `api-collection/Beckn-2.0-Discovr.postman_collection.json`.

### Step 7 — Report

Output a summary of changes made.

## Rules

- **beckn.yaml is the source of truth** for request/response schemas
- **Source code is the source of truth** for URL paths and HTTP methods
- **Never invent fields** not in beckn.yaml or source code
- **Preserve pre-request scripts** (signing logic) — only update if broken
- **Preserve collection variables** — only add new ones if needed
- **Keep existing `_postman_id`** — don't regenerate
- **Use realistic sample data** in request bodies (Beckn grocery domain examples)
- **All endpoints with bodies must have** `Content-Type: application/json` header
- **Error format** is always `{ errorCode, errorMessage }` — never `{ code, message }`
- **`catalogs` (plural)** in all response examples — never `catalog` singular
- **No underscore action variants** — only `discover`, `on_discover`, `catalog/push`
- **GET /beckn/discover** is synchronous (returns on_discover response directly)
- **POST /beckn/discover** is asynchronous (returns ACK, delivers on_discover via callback)
- **POST /catalog/push** is fire-and-forget (returns ACK, no callback)
- **Identity from auth header** — `subscriberId` and `recordId` from `Authorization: Signature keyId="subscriberId|recordId|algorithm"`
