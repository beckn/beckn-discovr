# Usage

How to discover resources across published catalogues using the Beckn Protocol v2.0.

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
    "bapUri": "<your-callback-url>",
    "networkId": "<network-id>"
  },
  "message": {
    "intent": { ... }
  }
}
```

| Field | Description |
|-------|-------------|
| `action` | Always `discover` for a discovery request |
| `messageId` | UUID correlating this request with its `on_discover` response |
| `transactionId` | UUID for the end-to-end interaction |
| `bapId` | Your consumer identifier |
| `bapUri` | Your callback URL — DISCOVR delivers results here via `POST /on_discover` |
| `networkId` | The network to search within |
| `message.intent` | The search criteria — see [Examples](examples.md) |

## Response Format

Results are delivered asynchronously to your `bapUri` as an `on_discover` callback:

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
    ],
    "inReplyTo": { "messageId": "..." }
  }
}
```

The response includes only catalogues, resources, and offers that match your query. Empty results return `"catalogs": []`.

## Synchronous Acknowledgement

When DISCOVR accepts the request, it returns an `ACK` synchronously:

```json
{ "message": { "status": "ACK", "messageId": "<uuid>" } }
```

If the request is invalid, it returns a `NACK`:

```json
{
  "message": {
    "status": "NACK",
    "messageId": "<uuid>",
    "error": {
      "code": "SCH_SCHEMA_VALIDATION_FAILED",
      "message": "context: must have required property 'transactionId'"
    }
  }
}
```

| Error Code | Meaning |
|------------|---------|
| `SCH_SCHEMA_VALIDATION_FAILED` | Request doesn't match the expected schema |
| `CTX_MISSING_FIELD` | A required context field is missing |
| `AUT_SIGNATURE_INVALID` | The request signature could not be verified |

## API Reference

| Operation | Method | Endpoint | Action |
|-----------|--------|----------|--------|
| **Discover resources** | POST | `/beckn/discover` | `discover` |
| **Receive results** (your endpoint) | POST | `<bapUri>/on_discover` | `on_discover` |
