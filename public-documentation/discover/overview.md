# Overview

DISCOVR searches across all catalogues published to the network. It supports natural language search, keyword search, location-based search, and attribute filtering — individually or combined.

Discovery searches the **full indexed catalogue** — it returns results regardless of when the data was published. No subscription is needed to use the discover API.

## How It Works

```
Consumer Node (CN)                          Beckn DISCOVR
     |                                           |
     |  Discover request                         |
     |  (text / spatial / JSONPath)              |
     |  POST /beckn/discover                     |
     |------------------------------------------>|
     |                                           |
     |       ACK (request accepted)              |
     |  <----------------------------------------|
     |                                           |  Searches indexed catalogues
     |                                           |
     |       on_discover callback                |
     |  POST /on_discover -> bapUri              |
     |  <----------------------------------------|
     |  (matching catalogues, resources, offers) |
```

Discovery is **asynchronous**:

1. The consumer sends a discover request to `POST /beckn/discover`.
2. DISCOVR validates the request and immediately returns an `ACK` (or a `NACK` if the request is invalid).
3. DISCOVR runs the search and delivers results to the consumer's callback URL (`bapUri`) via `POST /on_discover`.

## Search Modes

| Mode | Description | Example |
|------|-------------|---------|
| **Text / Natural Language** | Keyword or conversational query | "strong Assam tea for morning chai" |
| **Spatial** | Find resources near a location | Within 5 km of a GPS coordinate |
| **Attribute Filter** | Fine-grained filtering on resource or offer attributes | Flat discount offers under ₹100 |
| **Combined** | Mix any of the above in a single request | Coffee search within 5 km radius |

## Example Use Case

A consumer opens a grocery shopping app and searches for "instant coffee near me". The Consumer Node sends a discover request combining natural language text search with a spatial constraint (5 km radius around the consumer's location).

DISCOVR returns multiple matching products from different providers — each with competing offers showing different prices, discounts, and delivery estimates. The consumer compares offers and proceeds to order from the provider with the best price and fastest delivery.

See [Usage](usage.md) for request/response formats and [Examples](examples.md) for worked requests.
