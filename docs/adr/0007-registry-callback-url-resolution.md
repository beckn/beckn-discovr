# ADR-0007: Registry-Based Callback URL Resolution in Response Dispatcher

**Date**: 2026-05-26 (decision from commit fb8d4a5)
**Status**: accepted
**Deciders**: Beckn Discovr engineering team

## Context

The `on_discover` callback must be delivered to the BAP's registered URL. The naive approach is to read `context.bapUri` from the discovery request payload and POST directly to that URL. However, `bapUri` is supplied by the caller and could be set to any URL — including internal infrastructure endpoints — creating a Server-Side Request Forgery (SSRF) vulnerability. A malicious actor could set `bapUri` to `http://169.254.169.254/latest/meta-data/` (AWS metadata) or any internal service.

## Decision

The `response-dispatcher` resolves the BAP's callback URL from the DeDi Registry (via `becknAuth.getRegistryEntry(subscriberId)`) rather than trusting `context.bapUri` from the message payload. The `subscriberId` and `recordId` are propagated as Kafka message headers from the controller (set by `AuthorizationService` after signature verification) through the discover consumer to the response Kafka topic. The dispatcher reads these headers and resolves the verified URL. Falls back to `context.bapUri` only when auth is disabled (development mode).

## Alternatives Considered

### Alternative 1: Trust context.bapUri directly
- **Pros**: No registry lookup required; simpler code path
- **Cons**: SSRF risk — any URL can be injected via the request payload; Beckn network security depends on registry-verified identities
- **Why not**: SSRF is an OWASP Top 10 vulnerability; the Beckn auth model requires all participant URLs to be verified against the registry

### Alternative 2: Maintain a local allowlist of known BAP callback URLs
- **Pros**: No runtime registry calls; fast lookup
- **Cons**: Allowlist goes stale as BAPs register, update, or rotate their URLs; requires manual management
- **Why not**: DeDi Registry is the authoritative source of BAP URLs; a local copy introduces drift

### Alternative 3: Validate context.bapUri against the registry but still use it as the delivery URL
- **Pros**: Simpler validation — check that `bapUri` matches the registry entry rather than fetching the URL from the registry
- **Cons**: Allows an attacker who controls a registry entry to supply an arbitrary URL as long as it passes the prefix check
- **Why not**: The registry entry IS the authoritative URL; using it directly is more correct than validating a caller-supplied URL against it

## Consequences

### Positive
- SSRF eliminated — all callback URLs come from the DeDi Registry, not from the request payload
- BAP identity is cryptographically verified before delivery (Beckn HTTP Signature on the discover request → `subscriberId` propagated as Kafka header)
- Development mode (`auth.enabled=false`) falls back to `context.bapUri` without a registry call, preserving local development ergonomics

### Negative
- Registry lookup adds latency to the response-dispatcher path (mitigated by the async architecture — this is off the critical path from the BAP's perspective)
- If the registry is unavailable, callback delivery fails; requires retry logic in the dispatcher

### Risks
- `subscriberId` header missing from Kafka message (e.g., message produced before this change) would cause dispatcher to fail delivery. Mitigated by treating missing header as an auth-disabled fallback and logging a WARN.
