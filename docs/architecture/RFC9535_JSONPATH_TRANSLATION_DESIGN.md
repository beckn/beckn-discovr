# Design: RFC 9535 JSONPath Filtering with Pluggable DB Translation

**Status:** Draft — for architecture review (design validated by a working PoC, see §10)
**Service:** `catalog-discover-job` (Beckn Discovr)
**Author:** _(to fill)_
**Date:** 2026-06-25

---

## 1. Problem Statement

The discover service lets clients filter catalog resources using a JSONPath
expression carried in `message.intent.filters.expression`.

Two problems exist with the current implementation:

1. **The accepted syntax is not standards-compliant.** What the service
   advertises as "jsonpath" is actually **PostgreSQL SQL/JSON path**
   (SQL:2016) — not [RFC 9535](https://www.rfc-editor.org/rfc/rfc9535), the
   IETF standard for JSONPath. They look similar but differ in real ways
   (filter selector syntax, recursive descent, string-quoting, regex
   functions). Clients are unknowingly writing a PostgreSQL dialect.

2. **The filter capability is hard-wired to PostgreSQL.** The expression is
   string-munged for PG, wrapped in PG `jsonpath` syntax, executed via
   `CAST(? AS jsonpath)`, and even **validated** by asking PostgreSQL to parse
   it. If the underlying datastore is ever replaced (Elasticsearch, Cassandra,
   etc.), the entire filtering feature — including validation — breaks.

### Goal

- Accept **RFC 9535** as the canonical filter syntax (input contract).
- Translate RFC 9535 into the **target database's** query dialect through a
  **replaceable component** — so the database is not a hard constraint and a
  new datastore only needs a new translator.
- **Preserve backward compatibility:** existing clients send the current
  PostgreSQL dialect (`type: "jsonpath"`) and must keep working unchanged.
- **Validate the expression up front** on the **same `/discover` request path**
  (synchronous NACK before the request is queued), regardless of dialect.

### Non-Goals

- Rewriting the query execution engine, response pipeline, or callback flow.
- Migrating existing clients off the legacy dialect (they continue to work).
- Building non-PostgreSQL translators in the first phase (designed for, not
  built yet).

---

## 2. Current State (As-Is)

### 2.1 Request shape

```json
"filters": {
  "type": "jsonpath",
  "expression": "$.catalogs[*].resources[*] ? (@.resourceAttributes.connectorType == \"CCS2\")"
}
```

The `expression` above is **PostgreSQL SQL/JSON path**, not RFC 9535
(note the standalone `? (...)` filter form).

### 2.2 As-is flow

```
POST /beckn/discover  (sync, DiscoveryController)
  1. Auth
  2. Schema validation (beckn.yaml — paths["/discover"])
  3. IntentQueryValidator.validate()
        → JsonPathConverter.processFilter()   (string munge: ' → ", quote colon-fields)
        → probe Postgres: CAST(? AS jsonpath)  (PG is the grammar authority)
        → invalid → NACK (SCH_INVALID_JSONPATH)
  4. Publish to Kafka → ACK

DiscoveryEventConsumer  (async)
  5. JsonPathQueryBuilder.build()
        → wrap into exists($ ? (...)) / exists($path)
        → i.payload @@ CAST(? AS jsonpath)
  6. Execute → assemble catalogs → CatalogPipeline → on_discover callback
```

### 2.3 Where PostgreSQL coupling lives

| Coupling | Location | Effect of DB swap |
|---|---|---|
| String munging is PG-specific | `JsonPathConverter` | wrong output for other DBs |
| Expression wrapped in PG `jsonpath` | `JsonPathQueryBuilder` | not portable |
| `@@ CAST(? AS jsonpath)` execution | `QueryBuilderHelper` | PG-only operator |
| **Validation probes PostgreSQL** | `IntentQueryValidator` | validation breaks without PG |
| Filtering only runs on PG | `DiscoveryService` routing (J, J+G, J+T chain) | feature unavailable elsewhere |

### 2.4 Relevant existing facts

- `DiscoverRequest.Filter` **already has a `type` field** (currently always
  `"jsonpath"`). Nothing reads it yet — it is the natural dialect discriminator.
- Beckn spec (`beckn.yaml`) defines `filters.type` as an **`enum: [jsonpath]`,
  `default: jsonpath`**, with `additionalProperties: false`. It is an enum (not
  a `const`), so adding a value is an **additive, backward-compatible** change.

---

## 3. Proposed Design (To-Be)

### 3.1 Core idea — two seams

Cut the monolithic "munge-and-run-on-PG" step into a front-end and a back-end
joined by a database-neutral intermediate representation (AST):

```
            ┌─────────────┐      ┌──────────────┐      ┌──────────────────┐
 raw expr → │   PARSE     │ AST  │     IR       │      │   TRANSLATE      │ → engine query
 + dialect  │ (front end) │ ───▶ │ (DB-neutral) │ ───▶ │ (per-DB plugin)  │
            └─────────────┘      └──────────────┘      └──────────────────┘
              seam 1: dialect in                        seam 2: database out
```

- **Seam 1 (front end):** which syntax did the client send? Selected by
  `filters.type`. Legacy PG dialect and RFC 9535 are two parallel front ends.
- **Seam 2 (back end):** which database are we querying? One translator per
  engine, selected at query time.

### 3.2 Dialect routing (`filters.type`)

| `filters.type` | Front end | Notes |
|---|---|---|
| absent | Legacy PG | spec default — preserves existing behavior |
| `jsonpath` | Legacy PG | **unchanged path** — all current traffic |
| `rfc9535` | RFC 9535 | new, standards-compliant, opt-in |

`jsonpath` cannot be reassigned — every existing client and fixture sends it.
The standard path therefore takes a **new** value, `rfc9535`. This requires an
additive spec change (add `rfc9535` to the enum).

### 3.3 RFC 9535 front end — parser produces the AST

- The RFC publishes a complete **ABNF grammar**. We transcribe it into an
  **ANTLR4 grammar** (`.g4`); ANTLR generates a parser + visitor for the JVM
  (usable from Java and Scala).
- **Parsing IS validation.** If the text is not valid RFC 9535, the parser
  throws → clean NACK. If valid, it yields the AST.
- The parser is a single stateless bean, shared by the **validator** (API,
  request thread) and the **translator** (job, async consumer) — one source of
  truth for "what is valid RFC 9535".
- Compliance is proven by running the official **JSONPath Compliance Test
  Suite (CTS)** in CI — not asserted.

> **Why ANTLR and not a library?** No CTS-proven, AST-exposing RFC 9535 library
> exists for the JVM (Jayway is Goessner-dialect, not RFC 9535, and is an
> *evaluator* — it returns matches, not a tree we can translate). A Node.js
> rewrite could use `jsonpath-rfc9535` (CTS-pass, exposes an AST) instead, but
> the current stack is JVM (Java/Scala), so ANTLR is the fit.

### 3.4 Translator SPI — the pluggable database seam

Each translator walks the **same AST** and emits one engine's query form:

```java
public interface FilterTranslator {
    String engine();                                   // "postgresql", "elasticsearch", ...
    void assertSupported(FilterNode ast) throws UnsupportedFilterException;
    TranslatedFilter translate(FilterNode ast);        // AST → engine query fragment + params
}
```

- `PostgresFilterTranslator` emits a **PG `jsonpath` string** (then reuses the
  existing `@@ CAST(? AS jsonpath)` machinery — see §3.6).
- Adding Elasticsearch / Cassandra later = a **new bean only**; nothing else
  changes. The database is no longer a hard constraint.

### 3.5 Component overview

```
FilterExpressionParser   text → AST            (ANTLR; validation lives here)
FilterNode (sealed)      DB-neutral AST / IR
FilterTranslator         AST → one DB's query  (the plugin / SPI)
FilterTranslatorRegistry pick translator by engine
FilterDialect            pick front end by filters.type (legacy vs rfc9535)
```

### 3.6 Convergence with existing code (minimal blast radius)

The PG translator emits a **PG `jsonpath` string** — exactly the input the
existing builder already consumes. So both dialects converge:

```
jsonpath  → JsonPathConverter.processFilter() ─┐
rfc9535   → parse → AST → PostgresTranslator  ─┤→ JsonPathQueryBuilder → @@ CAST(? AS jsonpath)
                                                └────────── UNCHANGED ──────────────────────────┘
```

Everything from `JsonPathQueryBuilder` downward (the `exists()` wrapping, the
`jsonb_path_query_array` "matched offers" projection, schema/network filters,
SQL, assembly, pipeline) is **untouched**.

**Round-trip proof:** for the fixture above, the RFC 9535 form
`$.catalogs[*].resources[*][?@.resourceAttributes.connectorType == "CCS2"]`
translates to the existing PG string
`$.catalogs[*].resources[*] ? (@.resourceAttributes.connectorType == "CCS2")`
— byte-identical to what the legacy path already runs. This is the test that
validates the AST and translator are correct.

---

## 4. End-to-End Flow (All Java)

```
CLIENT  POST /beckn/discover
        filters: { type: "jsonpath" | "rfc9535", expression: "..." }
                                   │
                                   ▼
DISCOVER API (sync — DiscoveryController)
  1. Auth
  2. Schema validation (beckn.yaml)
  3. VALIDATE EXPRESSION (IntentQueryValidator) — branch on type:
        jsonpath → existing PG-probe (unchanged)
        rfc9535  → ANTLR parse-or-throw  (+ translator.assertSupported)
        invalid  → NACK (SCH_INVALID_JSONPATH), synchronous
  4. Write ORIGINAL { type, expression } to Kafka → ACK
                                   │  (Kafka carries the untranslated expression)
                                   ▼
JOB (async — DiscoveryEventConsumer)
  5. Read message
  6. TRANSLATE expression → DB dialect — branch on type:
        jsonpath → JsonPathConverter (string munge)
        rfc9535  → ANTLR parse → AST → PostgresTranslator
                          │
                          ▼  PG jsonpath string
  7. JsonPathQueryBuilder → i.payload @@ CAST(? AS jsonpath)   (UNCHANGED)
  8. Execute → assemble → CatalogPipeline
                                   │
                                   ▼
RESPONSE DISPATCHER → on_discover callback to BAP
```

**Why the original expression (not the translated PG) goes onto Kafka:** it
keeps the queued message **database-agnostic**. Translation happens at the job,
where the active engine is known. A future DB swap changes only step 6's
translator — the API, the Kafka contract, and the grammar stay put.

---

## 5. Validation Design

Validation **mirrors execution** and runs at the **existing single call site**
(`IntentQueryValidator.validate()` at `DiscoveryController` → `validateSchema`),
synchronously, before ACK. No new endpoint.

```
                EXECUTION (job)                       VALIDATION (API, pre-ACK)
jsonpath:  processFilter → PG string → @@        processFilter → PG string → PG probe (cached)
rfc9535:   parse → AST → translate → PG string   parse → AST → assertSupported → [PG probe]
```

Three layers for the `rfc9535` path:

1. **Grammar validation (parse)** — DB-neutral. Malformed RFC 9535 → NACK,
   with no DB round-trip.
2. **Capability validation (`assertSupported`)** — engine-specific. Valid
   RFC 9535 the engine cannot express → NACK with a precise reason.
3. **Final-authority probe** — for PG, translate → `CAST(? AS jsonpath)` probe
   (reuses the existing Caffeine-cached probe). Belt-and-suspenders.

Legacy `jsonpath` keeps its single existing layer (`processFilter` → PG probe),
unchanged.

> **Engine-bound layer 3:** the PG probe only applies while the engine is
> PostgreSQL. The SPI carries an optional per-engine `probe`/`dryRun` hook so a
> future ES-routed request validates via layers 1+2 (grammar + capability)
> without a PG probe.

---

## 6. Backward Compatibility

- **Contract:** two parallel front ends, **no retrofitting.** Legacy
  expressions are never re-parsed through the RFC 9535 grammar (that would
  silently change their meaning). They keep flowing through
  `JsonPathConverter` → PG exactly as today.
- **Default:** absent `type` ⇒ legacy (matches spec `default: jsonpath`). In
  practice every client already sends `type: "jsonpath"`, so the compat surface
  is simply "keep honoring `jsonpath`".
- **Spec:** adding `rfc9535` to the `filters.type` enum is additive; existing
  requests remain schema-valid.

---

## 7. Challenges & Risks

| # | Challenge | Detail | Mitigation |
|---|---|---|---|
| 1 | **Dialect semantics differ** | RFC 9535 filter selector `[?...]` vs PG standalone `? (...)`; `..` vs `.**`; single/double quotes vs double-only; `match()/search()` vs `like_regex`; `length()` vs `.size()`. | Per-construct mapping in the translator visitor; round-trip tests vs the legacy path on a real corpus. |
| 2 | **Regex flavor gap** | RFC 9535 regex is **I-Regexp (RFC 9485)**; PG `like_regex` is **POSIX**. The same pattern can match differently. | Decide policy: translate the common subset and **document divergence**, or **reject** unmappable patterns (capability NACK). Must be a conscious decision, not silent. **(OPEN — see §9)** |
| 3 | **Capability gaps** | Valid RFC 9535 PG cannot express: slice **with step** (`[::2]`), deep negative indices, string `length()`. | `assertSupported()` NACKs with a clear reason at validation time (sync), not as an async failure. |
| 4 | **Match-vs-nodelist semantics** | RFC 9535 evaluates to a **nodelist**; PG `@@` is boolean. | Unify as **"non-empty nodelist ⇒ match"**, which maps onto the existing `exists(...)` wrapping and the `jsonb_path_query_array` projection. |
| 5 | **Namespaced keys** | `schema:price` is not bare-legal in either dialect (today handled by regex munging). | In the AST it is an **opaque segment name**; each translator quotes it its own way (`."schema:price"` for PG). Removes the regex hack. |
| 6 | **Spec dependency** | `type: "rfc9535"` is rejected at schema validation until the enum is extended. | Ship the spec PR **with or before** the code; coordinate with the spec repo. |
| 7 | **Sync NACK for capability errors** | Grammar-only validation would let an un-translatable-but-valid expression ACK, then fail async (no callback) — the exact bug `IntentQueryValidator` prevents. | Run `assertSupported()` (layer 2) at the API, so capability failures NACK synchronously. |
| 8 | **No JVM RFC 9535 library** | Jayway is not RFC 9535 and is an evaluator (no AST). | Own the grammar via ANTLR; prove with the CTS. |
| 9 | **Injection / escaping** | The whole PG `jsonpath` is bound as one `?` param to `CAST` ⇒ no SQL injection. But string **literals** serialized into the path must escape `"` and `\`. | Translator escapes literals per `jsonpath` string rules; covered by tests. |
| 10 | **Two validators in sync** | API validator and job translator must agree on "valid". | Share the **same parser bean** across both call sites — single source of truth. |

---

## 8. Phasing / Rollout

1. **Phase 0 — Spec:** add `rfc9535` to `filters.type` enum (additive).
2. **Phase 1 — Front end:** ANTLR grammar + parser + AST; CTS in CI; wire
   `rfc9535` validation into `IntentQueryValidator`. Behavior gated to
   validation only (no execution change yet).
3. **Phase 2 — PG translator:** `PostgresFilterTranslator` over the AST;
   round-trip tests vs legacy; wire into the job. RFC 9535 fully functional on
   PostgreSQL.
4. **Phase 3 — (future) other engines:** `ElasticsearchFilterTranslator` over
   the same AST — also removes the J+T chain hack. Cassandra etc. as needed.

Legacy `jsonpath` remains the default and is untouched throughout.

---

## 9. Open Decisions (for review)

1. **Regex policy (Challenge #2):** translate-common-subset-and-document, vs
   reject-on-unmappable.
2. **AST node-set scope:** minimal (cover what real traffic uses today) vs
   full-RFC (cover the whole grammar, more `assertSupported` surface).
3. **Default-when-absent:** confirm legacy (recommended; matches spec default).
4. **Capability NACK at the edge:** confirm layer 2 runs at the API for sync
   parity (recommended).

---

## 10. PoC Validation (implemented & passing)

A working proof-of-concept was built on branch `jsonpath-rfc` (discover job only)
and run against a **real PostgreSQL** (Testcontainers `postgis/postgis:15-3.4`).
It implements the exact architecture above: an **ANTLR** RFC 9535 grammar →
parse tree (AST) → **`PgJsonPathEmitter`** visitor → PG `jsonpath` string, behind
the **`FilterTranslator`** SPI, with a Caffeine translation cache.

### Components built

```
build.gradle                                     ANTLR plugin + runtime
src/main/antlr/JsonPath.g4                        RFC 9535 grammar (subset)
src/main/java/org/beckn/discover/filter/
  FilterTranslator.java                           engine-pluggable SPI seam
  TranslatedFilter.java
  FilterParseException.java                       → SCH_INVALID_JSONPATH (grammar)
  UnsupportedFilterException.java                 → capability NACK
  rfc9535/Rfc9535PgTranslator.java                parser + Caffeine cache
  rfc9535/PgJsonPathEmitter.java                  RFC 9535 → PG visitor
  rfc9535/ThrowingErrorListener.java              fail-fast validation
src/test/java/.../Rfc9535PgTranslationIT.java     integration test
src/test/resources/rfc9535/valid_expressions.txt  curated corpus
```

### Results

| Test | Result | Evidence |
|---|---|---|
| Validity sweep | ✅ | 245 expressions, 245 accepted by PG `CAST(? AS jsonpath)`, 0 failures |
| **Full corpus executed on loaded data** | ✅ | **122 catalog docs loaded; 245 expressions EVALUATED via `jsonb_path_exists` against every doc (~29,900 path evaluations), 0 runtime errors, 207 matched ≥1 doc, 15,132 total hits** |
| Execution semantics | ✅ | 13 expressions run against seeded `jsonb` select the **correct** nodes (hit/miss matches expected) |
| Round-trip vs legacy | ✅ | RFC form translates **byte-identical** to the existing PG fixture form |
| Reject malformed RFC 9535 | ✅ | `FilterParseException` (missing `$`, unbalanced brackets, bad operators, …) |
| Reject unsupported | ✅ | `UnsupportedFilterException` (slice-step, `count()`) |

### Mappings verified against live data

filter selector `[?…]` → `? (…)`; recursive descent `..` → `.**`; namespaced
key `['schema:price']` → `."schema:price"`; single → double quotes; existence
`@.x` → `exists(@.x)`; `match()/search()` → `like_regex`; `length()` → `.size()`.

### Findings that refine the design

- **`count()` is genuinely unsupported on PG.** RFC `count()` counts *nodes in a
  nodelist*; PG `.size()` is *array length* — a different value. The PoC rejects
  `count()` via the capability gate rather than emit wrong PG. (Resolves a
  latent correctness trap.)
- **Regex flavor gap is real but deferred.** `match()/search()` patterns are
  currently passed through verbatim into `like_regex`. The I-Regexp (RFC 9485)
  vs POSIX divergence (Challenge #2) is **not** yet handled — still an open
  decision (§9.1).
- **Namespaced keys must use bracket notation in RFC 9535** (`['schema:price']`),
  not dot shorthand — which is cleaner than the legacy regex colon-munging and
  is handled correctly by the translator.

### PoC scope / not-yet-done

- Grammar is a **realistic subset**, not the full RFC ABNF. Production must
  complete it and run the official **JSONPath Compliance Test Suite** in CI.
- Not yet wired into `IntentQueryValidator` (validate) or the consumer
  (translate+execute) — the PoC is a standalone proof of the translation core.
- Regex-flavor handling and full slice/index edge cases pending.

---

## 11. Summary

- Input becomes standards-compliant (**RFC 9535**), selected by `filters.type`.
- A **DB-neutral AST** decouples syntax from storage.
- A **translator SPI** makes the database replaceable — new DB = new translator.
- **Validation** stays on the same `/discover` path, mirrors execution, reuses
  the existing probe/cache, and gains DB-neutral grammar checking.
- **Backward compatibility** is preserved by keeping the legacy PG path intact
  as a parallel front end — no existing client changes.
```
