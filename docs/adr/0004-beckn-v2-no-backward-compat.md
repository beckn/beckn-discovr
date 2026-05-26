# ADR-0004: Full Migration to Beckn Protocol v2.0 — No v1.0 Backward Compatibility

**Date**: 2026-05-26 (decision effective from schema redesign, commit c823013)
**Status**: accepted
**Deciders**: Beckn Discovr engineering team

## Context

Beckn Protocol v2.0 introduced breaking field renames across the Context, Resource, and Offer objects: `items` → `resources`, `itemAttributes` → `resourceAttributes`, `items` in offers → `resourceIds`, camelCase context fields replacing snake_case, and removal of `domain`, `schemaContext` (moved to `message.intent`), `country`, and `city` from the context object. Maintaining backward compatibility with v1.0 would require aliasing every renamed field, conditional deserialization branches, and a growing test matrix that slows all future changes.

## Decision

We migrate fully to Beckn Protocol v2.0 and remove all v1.0 backward compatibility. This means:

- `ContextNormalizer.java` deleted — no snake_case→camelCase normalization at runtime
- All `@JsonAlias` annotations for v1.0 field names removed from `Context.java`
- Field names in code, tests, fixtures, and ES mappings use v2.0 names exclusively (`resources`, `resourceAttributes`, `resourceIds`, `networkId` on context only)
- Schema validation runs against the v2.0 `beckn.yaml` spec, rejecting v1.0 payloads immediately

## Alternatives Considered

### Alternative 1: Maintain dual-version support with `@JsonAlias`
- **Pros**: Existing BPP integrations continue to work without update; gradual migration possible
- **Cons**: Every field that was renamed requires an alias; conditional logic proliferates; test fixtures must cover both shapes; the aliasing layer masks field mismatches in production
- **Why not**: The Beckn ecosystem was migrating to v2.0 simultaneously; supporting both versions would double the test surface area for a transitional period with no long-term value

### Alternative 2: Version the API (`/v1/discover`, `/v2/discover`)
- **Pros**: Clean version separation; v1 can be removed in a future release
- **Cons**: Two code paths to maintain; Beckn protocol does not specify versioned endpoint paths; BAPs would need to track which version each BPP is on
- **Why not**: Beckn's own spec does not use URL versioning; the `version` field in context carries the version; routing by URL would deviate from the spec

## Consequences

### Positive
- Single code path, no conditional deserialization logic
- Schema validation enforces v2.0 structure — invalid payloads are rejected with clear error messages at the controller, not silently coerced
- ES index mapping, PostgreSQL columns, and Java models all use consistent v2.0 naming

### Negative
- Any BPP or BAP still on v1.0 must update before integrating with Discovr
- In-flight Kafka messages with v1.0 field names will fail deserialization after the migration — requires a maintenance window or topic drain before deploying

### Risks
- A field that was aliased but not caught in review could silently accept v1.0 payloads. Mitigated by schema validation (`additionalProperties: false`) at the API boundary.
