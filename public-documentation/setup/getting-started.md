# Getting Started

This guide shows a consumer application (BAP) how to connect to a Beckn DISCOVR instance and run its first discover request.

> Looking to **run** DISCOVR rather than call it? See [Deployment Prerequisites](deployment-prerequisites.md).

## Prerequisites

Before you can call discover, you need:

| Item | Description |
|------|-------------|
| **DISCOVR base URL** | The hostname of the DISCOVR instance you are integrating with (e.g. `https://discovery.yourdomain.com`) |
| **`bapId`** | Your consumer (BAP) identifier, as registered on the network |
| **`bapUri`** | Your publicly reachable callback URL — DISCOVR delivers `on_discover` results here |
| **Signing key** | An Ed25519 key pair registered against your `bapId` for Beckn HTTP Signature authentication |
| **`networkId`** | The identifier of the network you want to search within |

## Step 1 — Prepare your callback endpoint

Discovery is asynchronous: DISCOVR acknowledges your request immediately, then delivers results to your `bapUri` via `POST /on_discover`. Your application must expose an endpoint that:

* Accepts `POST /on_discover` with the result payload (see [Usage](../discover/usage.md))
* Correlates the response to your request using `messageId` / `transactionId`
* Returns an `ACK`

## Step 2 — Send a discover request

Send your search intent to `POST /beckn/discover` on the DISCOVR base URL, signed with your Beckn HTTP Signature.

```bash
curl -X POST "https://discovery.yourdomain.com/beckn/discover" \
  -H "Content-Type: application/json" \
  -H "Authorization: <Beckn HTTP Signature>" \
  -d '{
    "context": {
      "version": "2.0.0",
      "action": "discover",
      "messageId": "55555555-5555-5555-5555-555555555555",
      "transactionId": "66666666-6666-6666-6666-666666666666",
      "timestamp": "2026-03-26T11:00:00Z",
      "bapId": "bap.myapp.in",
      "bapUri": "https://bap.myapp.in",
      "networkId": "retail-grocery"
    },
    "message": {
      "intent": { "textSearch": "Coffee" }
    }
  }'
```

DISCOVR responds synchronously with an `ACK`:

```json
{ "message": { "status": "ACK", "messageId": "55555555-5555-5555-5555-555555555555" } }
```

## Step 3 — Receive results

Shortly after the `ACK`, DISCOVR delivers matching catalogues, resources, and offers to your `bapUri` as an `on_discover` callback. See [Usage](../discover/usage.md) for the response shape and [Examples](../discover/examples.md) for more search modes.

## Postman Collection

A Postman collection and environment files are available under `api-collection/` in the repository to help you try requests against local, dev, and production instances.
