# Onix Gateway for Discovr — Requirements

**Story:** [#198 — Document Requirements for Beckn Onix as Discover Gateway](https://github.com/beckn/beckn-discovr/issues/198)
**Task:** [#199 — Document requirements for Beckn Onix as ION discovery gateway](https://github.com/beckn/beckn-discovr/issues/199)

---

## Overview

This document specifies the requirements for running **Beckn Onix** as the Beckn-protocol gateway in front of the **Discovr** stack on the ION network. Onix terminates all Beckn-protocol concerns (signature verification, signing, schema validation, network routing) at the edge so the Java services behind it only do domain work.

The primary loop is **BAP discover → Discovr → on_discover → BAP**. The same Onix instance also handles **catalog/push** (Catalg → Discovr, currently wired) and is intended to handle **catalog/subscription** + **catalog/pull** + **catalog/on_pull** (planned). Routing requirements for all flows are documented here so a single Onix deployment serves the full Discovr boundary.

This is a **requirements document** — it does not produce code. All behavioral claims are source-cited against `fidedocker/onix-adapter:1.6.0` (Onix) and the `beckn-discovr` repository.

---

## Scope

### In scope

- Onix module topology (`/receiver/` + `/caller/`) in one process
- Routing rules for:
  - Inbound `discover` (BAP → Discovr) — currently wired
  - Inbound `catalog/push` (Catalg → Discovr) — currently wired
  - Outbound `on_discover` (Discovr → BAP) — currently wired
  - Outbound `catalog/subscription` (Discovr → Catalg, sync) — planned
  - Outbound `catalog/pull` (Discovr → Catalg, async) — planned
  - Inbound `catalog/on_pull` (Catalg → Discovr, async ack) — planned
- Auth header semantics: `Authorization` and `X-Gateway-Authorization`
- Callback URL resolution for `on_discover` (`targetType: bap` via `context.bapUri`)
- networkId scoping decisions
- Docker Compose integration of `catalog-publish`, `catalog-discover-job`, `response-dispatcher`, and the supporting infra/proxy stack
- Open questions surfaced from Onix source tracing

### Out of scope

- Catalg-side Onix configuration (separate concern)
- TLS termination / certificate management (delegated to NPM)
- Multi-tenant network routing (one subscriber identity per Discovr instance)
- Implementation of subscription/pull initiation logic (this is a requirements doc; Story 7 builds the routing config, separate stories build any Java handlers)

---

## Functional Requirements

### FR-1: Inbound routing via `/receiver/` — Beckn protocol entrypoint

| ID | Requirement |
|----|-------------|
| FR-1.1 | The `/receiver/` module MUST accept HTTPS POST traffic from the public network via NPM and route by Beckn `context.version` + endpoint (the last segment of `url.Path`). Source: `pkg/plugin/implementation/router/router.go:215-283`. |
| FR-1.2 | For Beckn v2.x.x, `context.domain` is normalized to wildcard `*` and `context.networkId` is NOT consulted for route matching. Match is purely (version, endpoint). Source: `router.go:246-249`. |
| FR-1.3 | Required endpoint mappings (verified — currently wired): |
|  | • `endpoint=discover` → `http://catalog-discover-job:8080/beckn` |
|  | • `endpoint=push` → `http://catalog-publish:8080/catalog` |
| FR-1.4 | Required endpoint mappings (planned for subscription/pull): |
|  | • `endpoint=on_pull` → `http://catalog-publish:8080/catalog` (async ack handler, Java side not built yet) |
| FR-1.5 | Unknown endpoints MUST return a routing error and not forward. Source: `router.go:269-275`. |
| FR-1.6 | Receiver routing rules live in `onix-discover/routing-discover-receiver.yaml`, mounted into the container at `/app/config/`. |

### FR-2: Inbound signature verification

| ID | Requirement |
|----|-------------|
| FR-2.1 | Every `/receiver/` request MUST pass the `validateSign` step, which verifies the `Authorization` header against the signer's public key resolved via DeDi. Source: `core/module/handler/step.go:128-168`. |
| FR-2.2 | If `X-Gateway-Authorization` is present (chained gateway scenarios), it MUST be validated additionally before `Authorization`. Source: `step.go:131-138`. |
| FR-2.3 | The signer's identity is extracted from the keyId portion of the signature header (`subscriberId\|keyId\|alg` format), NOT from `context.bppId`. Source: `step.go:159-160`, `step.go:213-215`. |
| FR-2.4 | DeDi lookups MUST be cached via the `cache` plugin (Redis backend) to bound DeDi load. |
| FR-2.5 | Failed verification MUST return HTTP 401 with the `unauthorized` header set to a Beckn-compliant challenge. Source: `step.go:135,143,147`. |
| FR-2.6 | `Authorization` and `X-Gateway-Authorization` headers MUST be forwarded unchanged to the upstream Java service. `httputil.ReverseProxy` in `stdHandler.go:264-269` preserves all non-hop-by-hop headers by default. |

### FR-3: Outbound routing via `/caller/` — Discovr-initiated calls

| ID | Requirement |
|----|-------------|
| FR-3.1 | The `/caller/` module accepts traffic only from internal services (response-dispatcher and any operator-triggered admin tooling) and MUST NOT be exposed publicly. Onix's caller has no `validateSign` step; if exposed, any internet caller could request Onix to sign and forward arbitrary bodies under our DeDi identity. |
| FR-3.2 | Required endpoint mappings (verified — currently wired): |
|  | • `endpoint=on_discover` → `targetType: bap` → reads `context.bapUri` (or `bap_uri`) from body. Source: `router.go:239-241, 276-294`. |
| FR-3.3 | Required endpoint mappings (planned for subscription/pull): |
|  | • `endpoint=subscription` → `targetType: url` → Catalg URL (hard-coded, single catalog source) |
|  | • `endpoint=pull` → `targetType: url` → Catalg URL |
| FR-3.4 | If `targetType: bap`/`bpp` is used but the corresponding URI is absent from the body and no fallback `target.url` is configured, the router MUST return an error. Source: `router.go:287-291`. |
| FR-3.5 | Caller routing rules live in `onix-discover/routing-discover-caller.yaml`. |

### FR-4: Outbound signing

| ID | Requirement |
|----|-------------|
| FR-4.1 | Every `/caller/` request MUST pass the `sign` step, which signs the body with our Ed25519 private key and attaches the result as `Authorization`. Source: `step.go:38-76`. |
| FR-4.2 | Our role is `bpp`; outbound signs use the `Authorization` header (not `X-Gateway-Authorization`). The `X-Gateway-Authorization` variant is gated by `role == gateway` at `step.go:69-71`. |
| FR-4.3 | The signing identity (`ctx.SubID`) MUST be resolvable BEFORE the sign step runs. Resolution order: (a) reqpreprocessor middleware extracts `context.bppId` from body (role=bpp), (b) handler-level `subscriberId:` YAML field as fallback. Source: `stdHandler.go:213-219`. |
| FR-4.4 | `simplekeymanager` MUST find a local keyset keyed by the resolved `ctx.SubID`. If not found, signing MUST fail with `ErrKeysetNotFound`. Source: `simplekeymanager.go:218-243`. |
| FR-4.5 | Signature `expires` is 5 minutes after `created` (Onix-fixed; not configurable in v1.6.0). Source: `step.go:60`. |

### FR-5: BPP identity stamping by Discovr

| ID | Requirement |
|----|-------------|
| FR-5.1 | `catalog-discover-job` MUST stamp the configured BPP identity onto the outbound `on_discover` body's `context.bppId` and `context.bppUri` so that Onix-caller's `simplekeymanager` keyset lookup (which keys on `bppId` via `reqpreprocessor`) resolves to our local keyset. Source: `jobs/catalog-discover-job/src/main/java/org/beckn/discover/service/response/ResponseProcessor.java` `createResponseContext`. |
| FR-5.2 | The identity values are configured via env vars `DISCOVERY_BPP_ID` and `DISCOVERY_BPP_URI` on `catalog-discover-job`. |
| FR-5.3 | The same subscriberId value MUST appear in three places, all matching: (a) DeDi registry record, (b) `DISCOVERY_BPP_ID` env on `catalog-discover-job`, (c) `networkParticipant` in both `simplekeymanager` blocks of `discover-adapter.yaml`. |
| FR-5.4 | If `DISCOVERY_BPP_ID` is empty, `ResponseProcessor` MUST preserve whatever `bppId` came in on the request — opt-in stamping only. Source: same file. |

### FR-6: Static callback wiring for `on_discover`

| ID | Requirement |
|----|-------------|
| FR-6.1 | `response-dispatcher` MUST POST every outbound `on_discover` body to `http://onix-discover:8080/caller/on_discover` (internal docker DNS). Source: `STATIC_CALLBACK_URL` env, consumed at `jobs/response-dispatcher/src/main/java/org/beckn/seeker/service/HttpService.java:228-234`. |
| FR-6.2 | When `STATIC_CALLBACK_ENABLED=true`, the dispatcher MUST bypass DeDi lookup entirely. Source: `HttpService.java:228`. |
| FR-6.3 | The dispatcher MUST NOT sign outbound bodies itself (`SIGNING_ENABLED=false`) — signing is delegated to Onix-caller's `sign` step. |
| FR-6.4 | `CALLBACK_URL_VALIDATION_ENABLED=false` is required because the static callback target (`onix-discover`) is an internal hostname that the SSRF guard would otherwise reject. |
| FR-6.5 | The full URL constructed by the dispatcher is `${STATIC_CALLBACK_URL}${endpoint}` where `endpoint=/on_discover` for the `on_discover` action. Source: `HttpService.java:233`. |

### FR-7: Sync vs async response handling

Onix is sync/async-agnostic at the routing layer — whether a flow is sync or async is determined by the backend service the request is forwarded to, not by Onix itself. For `targetType: url`/`bap`/`bpp`, Onix uses a reverse-proxy that returns whatever the upstream returned (full body for sync APIs, ACK for async APIs).

| ID | Requirement |
|----|-------------|
| FR-7.1 | For `targetType: url` / `bap` / `bpp`, Onix MUST pass the upstream HTTP response (status, headers, body) back to the caller verbatim. Source: `stdHandler.go:227-230, 253-269` (`httputil.ReverseProxy`). |
| FR-7.2 | Sync flows (e.g., `catalog/subscription`): the upstream returns the result in the response body; Onix returns that body to the caller verbatim. |
| FR-7.3 | Async flows (e.g., `discover`, `catalog/push`, `catalog/pull`): the upstream returns an ACK; the actual result arrives later via a separate inbound callback through `/receiver/`. |
| FR-7.4 | The Discovr stack does NOT use Onix's `targetType: publisher` (queue-backed routing). Source: `stdHandler.go:231-251`. |

### FR-8: Schema validation

| ID | Requirement |
|----|-------------|
| FR-8.1 | Both `/receiver/` and `/caller/` MUST run `validateSchema` to validate the body against the Beckn v2.0 OpenAPI before forwarding. |
| FR-8.2 | The schema URL must be pinned to a specific Beckn protocol-specifications branch/tag, configured via `schemaValidator.config.location` per module. Schema URL pin is a deployment-time decision (see Open Questions). |
| FR-8.3 | Schema cache TTL MUST be at least 1 hour (`cacheTTL: "3600"`) to bound network calls to the schema source (typically GitHub raw). |
| FR-8.4 | Extended-schema overlays are configured but disabled by default (`extendedSchema_enabled: "false"`). When enabled, allowlisted hosts are `beckn.org,example.com,raw.githubusercontent.com`. |

### FR-9: NetworkId scoping

| ID | Requirement |
|----|-------------|
| FR-9.1 | Discovr's Onix instance MUST accept requests for the `networkId` value(s) it is configured to serve, and SHOULD reject foreign-network requests. |
| FR-9.2 | **Gap:** Onix v1.6.0 does NOT consult `context.networkId` in routing. The router only reads `context.domain` (normalized to `*` for v2.x.x) and `context.version`. There is no built-in allowlist for networkId. Source: `router.go:215-283`. |
| FR-9.3 | Until Onix supports networkId enforcement, the responsibility falls to the Java services behind it (`catalog-discover-job` validates `networkId` in `DiscoveryValidationService`). This is acceptable for a single-network deployment but degrades the "Onix as edge gateway" model. See Open Question Q1. |

---

## Integration Requirements (Docker Compose)

### IR-1: Deployment topology

A single `docker-compose` file deploys all services on a single VM on a shared docker bridge network (`beckn-network`). The only public ingress is NPM (ports 80/443); all other services are docker-network-only.

| Service | Public? | Role |
|---|---|---|
| `nginx-proxy-manager` | Yes — 80/443 (proxy), 81 (admin, firewall-restricted) | Host-routes all paths to `onix-discover:8080` |
| `onix-discover` | No (only via NPM) | Beckn protocol gateway, two modules: `/receiver/` and `/caller/` |
| `catalog-publish` | No | Handles `catalog/push` (and future `on_pull`, `on_subscription`) |
| `catalog-discover-job` | No | Handles `discover`, stamps BPP identity onto `on_discover` |
| `response-dispatcher` | No | Consumes on_discover from Kafka, posts to `/caller/on_discover` |
| `discovery-postgres` | Loopback-only (`127.0.0.1`) | Catalog data store |
| `discovery-elasticsearch` | Loopback-only | Search index |
| `discovery-kafka` | Loopback-only | Internal event bus |
| `discovery-redis` | Loopback-only | Onix `cache` plugin store |

### IR-2: End-to-end flow diagrams

#### Flow A — Inbound `discover` (BAP queries Discovr, async)

```
   ┌───────┐  POST /receiver/discover    ┌─────┐  forward     ┌─────────────────┐
   │  BAP  │ ──────────────────────────► │ NPM │ ───────────► │ onix /receiver/ │
   └───────┘  (signed body)              └─────┘              └─────────────────┘
       ▲                                                              │
       │  6. 200 {"status":"ACK"}                                     │
       │                                                              │  validateSign
       │                                                              │  addRoute
       │                                                              │  validateSchema
       │                                                              ▼
       │                                                  ┌───────────────────────┐
       │                                                  │  catalog-discover-job │
       │                                                  │   (POST /beckn/discover)
       │                                                  └───────────────────────┘
       │                                                              │
       │                                                              │ publish
       │                                                              ▼
       │                                                  ┌───────────────────────┐
       │                                                  │       Kafka           │
       │                                                  │ discovr.discover.in.* │
       │                                                  └───────────────────────┘
```

#### Flow B — Outbound `on_discover` (Discovr callback to BAP, async)

```
   ┌───────────────────────┐  consume   ┌───────────────────────┐  query/build/stamp
   │       Kafka           │ ─────────► │ catalog-discover-job  │ ─────────────────►
   │ discovr.discover.in.* │            │       (consumer)      │
   └───────────────────────┘            └───────────────────────┘
                                                    │
                                                    │ publish on_discover
                                                    ▼
                                        ┌─────────────────────────┐  consume   ┌──────────────────────┐
                                        │         Kafka           │ ─────────► │ response-dispatcher  │
                                        │ discovr.discover.out.*  │            └──────────────────────┘
                                        └─────────────────────────┘                       │
                                                                                          │  POST
                                                                                          │  /caller/on_discover
                                                                                          ▼
                                                                              ┌─────────────────────┐
                                                                              │   onix /caller/     │
                                                                              │  addRoute (bapUri)  │
                                                                              │  validateSchema     │
                                                                              │  sign               │
                                                                              └─────────────────────┘
                                                                                          │
                                                                                          │ POST <bapUri>/on_discover
                                                                                          ▼
                                                                                       ┌───────┐
                                                                                       │  BAP  │
                                                                                       └───────┘
```

#### Flow C — Inbound `catalog/push` (Catalg pushes catalog data, async)

```
   ┌────────┐  POST /receiver/push    ┌─────┐  forward     ┌─────────────────┐
   │ Catalg │ ──────────────────────► │ NPM │ ───────────► │ onix /receiver/ │
   └────────┘  (signed body)          └─────┘              └─────────────────┘
        ▲                                                          │
        │  200 ACK                                                 │  validateSign
        │                                                          │  addRoute
        │                                                          │  validateSchema
        │                                                          ▼
        │                                              ┌──────────────────────┐
        │                                              │   catalog-publish    │
        │                                              │ (POST /catalog/push) │
        │                                              └──────────────────────┘
        │                                                          │
        │                                                          │ persist + index
        │                                                          ▼
        │                                  ┌─────────────────┐  ┌─────────────────┐
        │                                  │   PostgreSQL    │  │  Elasticsearch  │
        │                                  └─────────────────┘  └─────────────────┘
```

#### Flow D — Outbound `catalog/subscription` (Discovr → Catalg, **sync**, planned)

```
   ┌─────────────────┐  POST /caller/subscription   ┌─────────────────┐  POST /catalog/subscription
   │    Operator     │ ───────────────────────────► │  onix /caller/  │ ────────────────────────────►  ┌─────────┐
   │ (SSH + docker)  │                              │   addRoute (url) │       (signed)                 │  Catalg │
   │                 │                              │   validateSchema │                                │         │
   │                 │                              │   sign           │                                └─────────┘
   │                 │                              └─────────────────┘                                       │
   │                 │                                       ▲                                                │
   │                 │  ◄──────────────────────────  reverse-proxy ◄────  200 { subscriptionId, status, ... } │
   │                 │  200 (verbatim sync body)             │            (sync result inline)                │
   └─────────────────┘                                       │                                                │
                                                             │                                                ▼
                                                          (sync passthrough preserves Catalg's response)
```

Caller returns the upstream response inline because `httputil.ReverseProxy` preserves the full response. Sync vs async is decided by the backend (Catalg), not by Onix.

#### Flow E — Outbound `catalog/pull` (Discovr → Catalg, async, planned)

```
   ┌──────────┐  POST /caller/pull   ┌─────────────────┐  POST /catalog/pull   ┌─────────┐
   │ Operator │ ───────────────────► │  onix /caller/  │ ────────────────────► │  Catalg │
   └──────────┘                      │   addRoute       │      (signed)         └─────────┘
        ▲                            │   validateSchema │                            │
        │                            │   sign           │                            │
        │ 200 ACK                    └─────────────────┘                            │
        │                                     ▲                                      │
        │  ◄────────────────────────  reverse-proxy ◄──────────────  200 ACK ───────┘
        │                            (ack passes back; actual data arrives later via Flow F + Flow C)
```

#### Flow F — Inbound `catalog/on_pull` (Catalg ack for the pull, async, planned)

```
   ┌────────┐  POST /receiver/on_pull   ┌─────┐  forward     ┌─────────────────┐
   │ Catalg │ ────────────────────────► │ NPM │ ───────────► │ onix /receiver/ │
   └────────┘  (signed)                 └─────┘              └─────────────────┘
                                                                      │
                                                                      │  validateSign
                                                                      │  addRoute
                                                                      │  validateSchema
                                                                      ▼
                                                          ┌──────────────────────────┐
                                                          │       catalog-publish    │
                                                          │ (POST /catalog/on_pull —  │
                                                          │  handler TBD; correlates  │
                                                          │  ack with pull txnId)     │
                                                          └──────────────────────────┘
```

Actual catalog data arrives separately via **Flow C** (`catalog/push`). `on_pull` carries only the protocol-level acknowledgment.

### IR-3: How `catalog-discover-job` sits behind Onix

| ID | Requirement |
|----|-------------|
| IR-3.1 | `catalog-discover-job` MUST NOT bind a public host port; `expose: "8080"` only. Reachable from Onix via docker DNS. |
| IR-3.2 | `SIGNATURE_AUTH_ENABLED=false` — incoming signature verification is delegated to Onix's `/receiver/validateSign`. |
| IR-3.3 | `BECKN_PROTOCOL_API_SCHEMA_URL` is set to the same URL as Onix's `schemaValidator.location` so both sides use the same canonical schema. |
| IR-3.4 | `DISCOVERY_BPP_ID` and `DISCOVERY_BPP_URI` MUST be set when running behind Onix, so that on_discover responses carry the BPP identity required by Onix-caller's signing path. |

### IR-4: How `response-dispatcher` sits behind Onix

| ID | Requirement |
|----|-------------|
| IR-4.1 | `response-dispatcher` MUST NOT bind a public host port; `expose: "8080"` only. |
| IR-4.2 | `STATIC_CALLBACK_ENABLED=true` + `STATIC_CALLBACK_URL=http://onix-discover:8080/caller` — every outbound on_discover routes through Onix instead of being delivered directly to a DeDi-resolved BAP URL. |
| IR-4.3 | `SIGNING_ENABLED=false` — outbound signing is delegated to Onix-caller's `sign` step. |
| IR-4.4 | `CALLBACK_URL_VALIDATION_ENABLED=false` — required to permit POSTing to the internal hostname `onix-discover`. |

### IR-5: How `catalog-publish` sits behind Onix

| ID | Requirement |
|----|-------------|
| IR-5.1 | `catalog-publish` MUST NOT bind a public host port; `expose: "8080"` only. |
| IR-5.2 | `SIGNATURE_AUTH_ENABLED=false` — incoming signature verification is delegated to Onix. |
| IR-5.3 | When `catalog/on_pull` and `catalog/on_subscription` handlers are added (separate stories), they MUST also route through Onix's `/receiver/`, NOT receive traffic directly. |

### IR-6: Supporting infrastructure

| ID | Requirement |
|----|-------------|
| IR-6.1 | `discovery-postgres`, `discovery-elasticsearch`, `discovery-kafka`, `discovery-redis` MUST bind their host ports to `127.0.0.1` only — never to `0.0.0.0`. |
| IR-6.2 | The Kafka service name MUST be unique on `beckn-network` (`discovery-kafka`, not `kafka`) to prevent DNS-resolution collision when colocated with other Kafka-bearing stacks. Verified failure mode: round-robin DNS sent the dispatcher to a different stack's `kafka` and the consumer group never received messages. |
| IR-6.3 | NPM is the only service that binds public host ports (80, 443, 81). Port 81 (admin UI) MUST be firewalled to operator IPs. |
| IR-6.4 | NPM's `Block Common Exploits` MUST be OFF — its header injection would break Beckn HTTP signatures. |

### IR-7: Adapter config volume mount

| ID | Requirement |
|----|-------------|
| IR-7.1 | The directory `./onix-discover/` MUST be mounted at `/app/config` inside the Onix container. Contains `discover-adapter.yaml`, `routing-discover-receiver.yaml`, `routing-discover-caller.yaml`. |
| IR-7.2 | Onix CMD MUST be set explicitly because the v1.6.0 image has no `ENTRYPOINT`. Use: `command: ["./server", "--config=/app/config/discover-adapter.yaml"]`. (Verified — the image's default `CMD` depends on a `CONFIG_FILE` env var; if both are unset the container fails to start.) |

---

## Architectural Constraints

### AR-1: Onix role constraints (source-verified)

| ID | Constraint |
|----|-----------|
| AR-1.1 | Onix defines five roles: `bap`, `bpp`, `gateway`, `discovery`, `registery`. Source: `pkg/model/model.go:111-131`. |
| AR-1.2 | Only `bap` and `bpp` are usable with the standard plugin set. `reqpreprocessor` middleware (used in every standard module) explicitly rejects any other role at startup. Source: `pkg/plugin/implementation/reqpreprocessor/reqpreprocessor.go validateConfig`: `if cfg.Role != "bap" && cfg.Role != "bpp" return error`. |
| AR-1.3 | `gateway` is the only role with a distinct runtime branch (sign step emits `X-Gateway-Authorization` instead of `Authorization`). `discovery` and `registery` are dead enums — defined but no code reads them. |
| AR-1.4 | Discovr modules MUST use `role: bpp` on both `/receiver/` and `/caller/`. Discovr plays the BPP role relative to BAPs; outbound `on_discover` is signed under our BPP identity. |

### AR-2: SubID resolution chain (source-verified)

| ID | Constraint |
|----|-----------|
| AR-2.1 | `ctx.SubID` is resolved by `stdHandler.subID(ctx)` at `stdHandler.go:213-219` in this order: (a) `ctx.Value(ContextKeySubscriberID)` if set by reqpreprocessor, (b) `h.SubscriberID` from handler config (`subscriberId:` YAML key). |
| AR-2.2 | The `sign` step rejects empty SubID with `subscriberID not set` at `step.go:40-42`. Identity must be resolvable BEFORE sign runs. |
| AR-2.3 | The `simplekeymanager.Keyset(keyID)` lookup rejects empty keyID with `ErrEmptyKeyID` — no fallback. Source: `simplekeymanager.go:218-229`. |

### AR-3: Module separation

| ID | Constraint |
|----|-----------|
| AR-3.1 | `/receiver/` and `/caller/` MUST be separate Onix modules in the same process, with separate plugin lists, separate routing configs, and separate step lists. |
| AR-3.2 | `/receiver/` steps: `validateSign`, `addRoute`, `validateSchema`. `/caller/` steps: `addRoute`, `validateSchema`, `sign`. |
| AR-3.3 | The two modules MUST share the `cache` plugin's Redis backend (same `addr: redis:6379`) so the DeDi cache is consistent across hops. |
| AR-3.4 | The two modules share a single `simplekeymanager` keyset (single subscriber identity). The same key material appears in both module's plugin config blocks but represents one logical identity. |

### AR-4: Operator access model for `/caller/`

| ID | Constraint |
|----|-----------|
| AR-4.1 | `/caller/` MUST NOT be exposed via NPM. The caller has no inbound signature check; public exposure permits signature forgery under our DeDi identity. |
| AR-4.2 | Internal stack services (response-dispatcher today, future Java admin endpoints) reach `/caller/` over the docker network — no extra config needed. |
| AR-4.3 | One-shot operator triggers (subscription, pull) MUST come via SSH + `docker exec` against an internal container, OR via a yet-to-be-defined admin path with IP allowlist. See Open Question Q4. |

---

## Open Questions for Beckn Onix Team

These are gaps surfaced during source-tracing that we cannot resolve from the Discovr side alone.

### Q1: NetworkId allowlist enforcement

Onix's router does not consult `context.networkId` for routing or filtering decisions. The Discovr "gateway" model implies networkId-level isolation, but today there's no built-in mechanism — the Java services behind Onix must validate networkId themselves.

**Ask:** Either (a) extend router config with `acceptedNetworkIds: [...]` allowlist, or (b) document the recommended pattern for a custom middleware plugin that does this check.

### Q2: Role semantics for `discovery` and `registery`

The roles `discovery` and `registery` exist in the enum but trigger zero behavioral branches in the codebase. They also cannot be used with `reqpreprocessor` (which rejects them at startup).

**Ask:** Either (a) wire `discovery` role to extract subID from a config-defined or alternate-body source, or (b) deprecate these enum values to avoid confusion about what roles are usable.

### Q3: `networkParticipant` vs `subscriberId` rename

`fidedocker/onix-adapter:1.6.0` uses `networkParticipant` as the simplekeymanager config key. Onix `main` has renamed to `subscriberId` (commit `6e933d6`, PR #705).

**Ask:** Confirm when the rename will land in a released image. Provide deprecation timeline so deployment configs can migrate without ambiguity.

### Q4: Operator access pattern for `/caller/`

`/caller/` has no inbound auth gate. Discovr operators need a way to trigger one-shot outbound calls (subscription/pull) without exposing `/caller/` publicly.

**Ask:** Document the recommended pattern. Candidates: (a) SSH + `docker exec` (current default), (b) a dedicated NPM proxy host with IP allowlist for an `/admin/` path, (c) a future Onix feature for module-level access control.

### Q5: Schema URL pin

Discovr currently uses `protocol-specifications-v2/refs/heads/main/api/v2.0.0/beckn.yaml`. Onix samples have used `core-v2.0.0-lts` and other branches.

**Ask:** Confirm the canonical schema URL for ION production deployments. If different from Discovr's current choice, identify the field-level diffs that matter for `validateSchema` step compatibility.





---

## Decisions Pending (Discovr-side)

These are choices that block production deployment but don't require Onix-team input.

| ID | Decision | Owner | Blocker for |
|----|----------|-------|-------------|
| D-1 | Subscriber ID for Discovr's DeDi record | Network governance + Discovr ops | First production deploy |
| D-2 | Public DNS name for the deployment IP | Ops / DNS owner | TLS via Let's Encrypt (won't issue for bare IP) |
| D-3 | Operator-access model for `/caller/` (SSH-exec vs NPM allowlist vs admin endpoint) | Eng + Ops | First subscription/pull trigger |
| D-4 | Schema URL pin | Discovr eng + Onix team | Production validateSchema config |
| D-5 | Image tag policy for the three Discovr services | Release eng | Tagging convention in compose |
| D-6 | Whether to bump past Onix `#705` (rename `networkParticipant` → `subscriberId`) | Discovr ops + Onix release | Configuration drift |

---

## Acceptance Verification

This document is considered complete when the following acceptance criteria (from Task #199) are met:

| AC | Coverage in this doc |
|---|----|
| Requirements document created at `docs/requirements/REQ-onix-gateway.md` | ✅ This file |
| Onix routing rules (which endpoints proxied) | FR-1 (inbound), FR-3 (outbound) |
| Auth header pass-through (`Authorization`, `X-Gateway-Authorization`) | FR-2, FR-4.2, FR-2.6 |
| BAP callback URL routing from `on_discover` response | FR-3.2, FR-6, FR-5 |
| Network ID scoping | FR-9, Open Question Q1 |
| Docker Compose integration of `catalog-discover-job` and `response-dispatcher` | IR-1, IR-3, IR-4 (plus end-to-end flow diagrams in IR-2) |
| Known gaps and open questions for Onix team | Q1–Q7 |
| Document reviewed and approved by the team before Story 7 begins | ⏳ Pending team review |
