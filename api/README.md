# on_discover API

Lightweight Node.js + Express + TypeScript service that exposes **POST /on_discover** to receive discovery responses from the catalog service, optionally verify signature (stub), and produce the message to a Kafka topic. Schema validation is implemented but currently disabled; re-enable by adding `validateOnDiscoverRequest` to the route in `src/routes/routes.ts`. Additional APIs can be added there too.

## Structure

- `src/server.ts` – Entry: Express app, routes, listen, graceful shutdown
- `src/config/` – Env configuration
- `src/controller/` – Request handlers
- `src/middleware/` – Schema validation, signature verification (stub)
- `src/routes/` – Route definitions
- `src/lib/` – Logger, metrics, Kafka producer
- `schemas/` – JSON Schema for request validation

## Run

```bash
cd api
npm install
npm run build
npm start
```

Development (watch):

```bash
npm run dev
```

**Integration tests** (require Docker):

```bash
npm run test:integration
```

Uses Testcontainers to start a real Kafka container, then runs tests for POST /on_discover and GET /health.

## Environment

| Variable | Description | Default |
|----------|-------------|---------|
| `PORT` | HTTP server port | `3000` |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka brokers | required |
| `KAFKA_ON_DISCOVER_TOPIC` | Topic to produce on_discover messages to | required |
| `SIGNATURE_VERIFICATION_ENABLED` | Enable signature verification (`true`/`false`) | `false` |
| `LOG_LEVEL` | Log level (e.g. `info`, `debug`) | `info` |
| `NODE_ENV` | `development` / `production` | `development` |

Copy `.env.example` to `.env` and set values (or export in shell).

## Endpoints

- **POST /on_discover** – Accept discovery response body (DiscoverResponse shape). Optionally runs signature verification (stub); produces to Kafka. Returns `202` with `{ "status": "accepted" }` on success; `400` on signature failure; `5xx` on Kafka or internal errors. Schema validation can be re-enabled in the route.
- **GET /metrics** – Prometheus metrics (request count, duration, Kafka success/failures; validation metrics when validation is enabled).

## Signature verification

When `SIGNATURE_VERIFICATION_ENABLED=true`, the service calls the signature verification module; **currently this is a stub that always succeeds**. Real Beckn HTTP signature verification (e.g. BLAKE-512 + Ed25519) can be implemented later in `src/middleware/signature.ts` without changing the route contract.
