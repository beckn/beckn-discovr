# Beckn Discovr

> **Note:** "Discovr" — without the middle "e" — is the name of this service, not a typo for "Discover" (the Beckn protocol action it implements is `discover`/`on_discover`).

Beckn Discovr is a **reference implementation of the Discovery Service (DS)** for the Beckn ecosystem: the catalog **discovery → query → dispatch** pipeline that lets Consumer Nodes (CNs) search catalog data published across a Beckn network and get results back asynchronously.

Consumer Nodes send a `discover` request → Discovr queries its catalog index → Discovr delivers matching resources and offers via an `on_discover` callback.

## Reference implementation, not a mandate

This repository is one working implementation of the Discovery Service role in a Beckn network — built to demonstrate the protocol end-to-end and to be usable as-is. It is **not** the only correct way to build a Discovery Service.

Network Participants are free to:

- **Deploy this repository directly** in their own network environment, or
- **Build their own Discovery Service** from scratch, using this repo only as a reference for protocol behavior, query routing, and response shaping,

as long as the `/discover` → `on_discover` contract and the underlying Beckn Protocol v2.0 schemas are honored. Nothing about participating in a Beckn network requires running this exact codebase.

## Why a dedicated discovery layer

Without a purpose-built discovery service, consumer-facing applications are left to query raw catalog data directly, build their own per-application search indexes, and reinvent spatial/attribute/text search — all while getting an inconsistent, siloed view of what's actually available across providers. Discovr exists to remove that burden:

- **One query surface** across all catalogs indexed into the network
- **Multiple search modes** — JSONPath attribute filtering, spatial/geo search, and text/semantic search — usable individually or combined
- **Provider-agnostic results** — resources and competing offers from multiple providers in a single response
- **Schema-aware responses** — results are filtered, deduplicated, and pruned according to the requested schema context

## Components

| Component | Path | Stack |
|-----------|------|-------|
| Catalog Discover Job | `jobs/catalog-discover-job/` | Java 17 · Spring Boot · PostgreSQL/PostGIS · Elasticsearch · Kafka |
| Catalog Publish Job | `jobs/catalog-publish-job/` | Java 17 · Spring Boot · Kafka · PostgreSQL · Elasticsearch |
| Response Dispatcher | `jobs/response-dispatcher/` | Java 17 · Spring Boot · Kafka · RestTemplate |

## Documentation

- [`docs/`](docs/) — architecture, ADRs, requirements, design specs, and reference guides. Start at [`docs/README.md`](docs/README.md) for the full index.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — how to build, test, and submit changes.
- [`SECURITY.md`](SECURITY.md) — how to report a vulnerability.

## Quick start

```bash
docker network create beckn-network   # one-time setup
docker compose up -d
# catalog-discover-job: http://localhost:8082
# catalog-publish-job:  http://localhost:8085
```

See [`docs/reference/USER_GUIDE.md`](docs/reference/USER_GUIDE.md) for sending your first `discover` request.

For standing up a full public-facing VM deployment (Onix adapter in front, Nginx Proxy Manager, DeDi registration, etc.), see [`discovr-deployment/DEPLOYMENT.md`](discovr-deployment/DEPLOYMENT.md).

## License

[MIT](LICENSE)
