# Design: Discover API Filter Grammar Conformance to RFC 9535 (JSONPath)

**Status:** PROPOSED
**Issue:** [beckn/beckn-discovr#470](https://github.com/beckn/beckn-discovr/issues/470)
**Date:** 2026-09-02
**Scope:** `jobs/catalog-discover-job` only

---

## Problem

The Discover API's `message.intent.filters.expression` is currently validated and executed as **raw PostgreSQL SQL/JSON path** syntax — Postgres's own vendor `jsonpath` type, consumed via `i.payload @@ CAST(? AS jsonpath)` and `jsonb_path_query_array(i.payload, CAST(? AS jsonpath))`.

Because Discovr uses PostgreSQL for spatial + attribute filtering, the **wire-format grammar** ended up coupled to Postgres's vendor dialect rather than to an open standard. We want the wire contract to be **RFC 9535** ("JSONPath: Query Expressions for JSON", IETF, Feb 2024).

### Current flow (grounded)

```
POST/GET /beckn/discover
  → DiscoveryController.validateSchema()
      → DiscoveryValidationService: structural + absoluteness guard
          (expression must be non-blank and start with "$"  — DiscoveryValidationService.java L326-343, not the controller)
      → IntentQueryValidator.validate()
          → JsonPathConverter.processFilter()  (single→double quotes, colon-field auto-quoting)
          → probe: SELECT CAST(<processed> AS jsonpath)   ← Postgres is the grammar authority
          → verdict memoised in Caffeine (10k, unbounded TTL)
          → invalid ⇒ NACK  code=SCH_INVALID_JSONPATH
  → (async) JsonPathQueryBuilder.build()
      → JsonPathConverter.processFilter() again
      → toPostgresFilter(): wraps as exists($ ? (...)) or absolute path
      → binds as ? into JSONPATH_MATCH / BASE_SELECT_WITH_FILTER_RESULT
```

The grammar authority today is literally "whatever `CAST(... AS jsonpath)` accepts." The goal is to make **RFC 9535 the wire grammar**, compile the supported subset into typed SQL predicates (see "DECISION — pure T2" below — not Postgres's `jsonpath` dialect), and reject (never silently degrade) the untranslatable subset.

---

## Decided scope (not re-litigated — see issue #470 discussion)

This is the **"A-first, C-shaped"** pass:

1. **Parse layer** — embed an RFC 9535 parser; parse `intent.filters.expression` into an AST.
2. **Compilation layer ("A")** — walk the AST and compile ONLY the cleanly-mappable subset into typed, parameterized SQL predicates using plain `jsonb` operators (**pure T2 — see DECISION below**; not Postgres's `jsonpath` dialect), feeding into `QueryBuilderHelper.QueryTemplate.condition(...)` alongside the existing spatial/network/active/validity/schema conditions. PostgreSQLQueryEngine architecture and the J+G combined-SQL path stay intact — with one caveat worth flagging up front rather than only in the Risks section: offers-projection may require `QueryTemplate.build()` to support `GROUP BY` for the first time (see the worked example and Risk 8 below), which is a real structural addition, not a null-risk claim.
3. **Denylist ("C-shaped, B deferred")** — untranslatable constructs are **REJECTED with a NACK**, never silently ignored, never fetch-then-filter in memory. The denylist is explicit and documented in code behind a clear seam, so a future in-memory fallback ("B") is additive.
4. **NACK details** — reuse the beckn.yaml ACK/NACK contract; each denylisted construct and invalid-syntax get their own dedicated `SCH_*` error code (see Error handling).
5. **Dual-grammar migration** — RFC 9535 **and** legacy Postgres jsonpath both keep working via **parse-priority auto-detection** (RFC 9535 first, legacy Postgres probe as fallback, legacy path unmodified). No new request field, no client-visible grammar tag. Tag each request with the grammar path served (metrics/logs) for migration visibility. Legacy support is transitional/unversioned.

**Out of scope this pass:** array slice-with-step `[start:end:step]`, `count()`, `value()`, `match()`/`search()`, **descendant segment `..`** (added after the T1-vs-T2 spike — see DECISION below), and the in-memory "B" fallback itself.

---

## Genuine open choice: RFC 9535 parser library

This is the only decision within-scope with a real implementation fork. Everything else is determined by the scope above, so the rest of this document is a direct spec.

**Vetted 2026-09-04 via documentation/README (`gh api`); corrected 2026-09-07 via direct code-level inspection** — the jar + sources jar for each real candidate were downloaded from `repo1.maven.org` (the canonical, actually-resolvable Maven Central mirror — `search.maven.org`'s Solr search index was found to be stale/unreliable and gave false negatives during the first vetting pass; always verify artifact existence against `repo1.maven.org/maven2/<group-path>/<artifact>/maven-metadata.xml` directly, not the Solr search UI/API) and their source read directly, not inferred from README/Javadoc.

### Option P1 — `snack4-jsonpath` (org.noear / SnackJson) — VIABLE, corrected verdict

| Field | Content |
|-------|---------|
| Coordinates | `org.noear:snack4-jsonpath:4.0.59` (+ `org.noear:snack4` for the JSON DOM layer, `ONode`) |
| License | **Confirmed Apache-2.0** |
| Java | **Confirmed** — genuine Java (not JVM-hosted Kotlin), JDK 8/11/17/21/25 badges, no interop layer needed |
| Maven Central | **Confirmed published**, re-verified directly against `repo1.maven.org/maven2/org/noear/snack4-jsonpath/maven-metadata.xml` (latest `4.0.59`) — the first vetting pass's "no artifact found" was a false negative from `search.maven.org`'s Solr index, not the actual repository state |
| Last push | **2026-08-05** — actively maintained |
| RFC 9535 coverage | Comprehensive — every RFC 9535 selector, operator, and function (`length`, `count`, `match`, `search`, `value`, slice-with-step) implemented, plus Jayway-compatible mode |
| Walkable AST | **Partially confirmed — a real, public, walkable *segment*-level AST, but filter-predicate internals are not exposed.** Verified by reading the actual source: `JsonPathParser.parse(path): JsonPath` is a public static entry point, no wrapper hiding it. `JsonPath.getSegments(): List<Segment>` is public, and concrete segment types (`SelectSegment`, `FuncSegment`, `DescendantSegment`) are public classes with `getOriginalText()` giving the segment's raw source text and `instanceof` giving its kind. This correctly and robustly handles the hardest lexing problems (nested brackets, quotes, escapes, regex literals, balanced parens/braces in function args) — verified by reading `JsonPathParser.parseSegment()` in full. **The gap:** `SelectSegment`'s internal `List<Selector> selectors` is `private` with no getter, and `Expression` (the filter-predicate evaluator, `org.noear.snack4.jsonpath.filter.Expression`) stores its parsed RPN token list in a `private final List<Token> rpn` with package-private `Token` fields — so a bracket segment's selector kind (index/name/wildcard/slice/filter) and a filter predicate's internal comparison/operator structure are not retrievable through any public accessor; only the segment's raw text is. |

### Option P4 — `jsonpath4k` (A-SIT Plus) — DISQUALIFIED, corrected verdict

| Field | Content |
|-------|---------|
| Coordinates | `at.asitplus:jsonpath4k-jvm` — **latest published Maven Central version confirmed 2.4.1** (`repo1.maven.org/maven2/at/asitplus/jsonpath4k-jvm/maven-metadata.xml`, `versionCount: 4`) |
| License | Apache-2.0 |
| Java | Java 17+ badge, but Kotlin Multiplatform — JVM interop, not native Java |
| Walkable AST | **Confirmed FAILS the gate, at the only publishable version.** Read the actual `2.4.1`-tagged source directly (`gh api ".../contents/...?ref=2.4.1"`): `JsonPathSelector` is declared `internal sealed interface` (Kotlin `internal` = not part of the module's public API, unreachable from Java or any consuming module), and `JsonPath`'s compiled query is a `private val` with no accessor. **Important nuance:** the GitHub repository's unreleased development state (tags up to `4.0.0`) has since made `JsonPathSelector` public — but that state was never published to Maven Central (still frozen at `2.4.1`), so it isn't usable as a real dependency without vendoring/building from source, which is the same unacceptable supply-chain posture that disqualified P2. Do not be misled by browsing the GitHub default branch without pinning a version — that was the exact mistake made and caught during this correction. |

### Option P2 — `java-jsonpath` (rob-ross port of python-jsonpath) — DISQUALIFIED

**No confirmed Maven Central artifact found**, verified against `repo1.maven.org` directly (not just the unreliable Solr search). Whatever the code quality, this fails the practical "usable Java 17 dependency" bar as-is — pinning it would mean vendoring/building from source, an unacceptable supply-chain posture for a load-bearing dependency. Dropped from consideration.

### Option P3 — Own thin ANTLR/hand-rolled RFC 9535 grammar (fallback, now de-scoped)

| Field | Content |
|-------|---------|
| Fit | Full control of AST shape; grammar is small (RFC 9535 ABNF ~2 pages) |
| Risk | We maintain a spec parser; highest effort; easy to get I-JSON/number edge cases wrong |
| **Revised scope, given P1's viability** | **No longer "hand-roll the entire grammar."** P1 (`snack4-jsonpath`) correctly solves the hardest lexing problem (segment splitting with quote/escape/nesting robustness) via its public `getSegments()`. What remains for us to write ourselves, using each segment's `getOriginalText()` as raw input: (a) a thin bracket-segment classifier (index/name/wildcard/slice/filter — a first-char dispatch, ~10-20 lines, mirroring the trivial logic already visible in `SelectSegment`'s own constructor), and (b) a filter-predicate parser/compiler for `?(...)` content (comparisons, `&&`/`||`/`!`) — which is genuinely the core of "compile RFC 9535 filters to SQL" regardless of parser choice, not extra work created by the gap. |

### Selection criteria (apply at implementation time)

**Hard gate (must pass all):** Java 17 build; permissive license (Apache-2.0/MIT/BSD); exposes a **walkable AST** (not eval-only) so the denylist seam can inspect construct types; a release within ~18 months OR a maintained fork; **verified against `repo1.maven.org` directly, never `search.maven.org`'s search UI/API alone.**

| Criterion | Weight | P1 snack4-jsonpath | P4 jsonpath4k | P3 own grammar |
|-----------|--------|---------------------|----------------|-----------------|
| Walkable AST (enables the seam) | 30% | 4 (segment-level confirmed public; filter-predicate internals still self-written) | 1 (confirmed `internal` at the only published version) | 5 |
| RFC 9535 correctness fidelity | 25% | 5 (comprehensive documented + verified coverage) | 5 (moot — disqualified) | 3 |
| Maintenance / bus factor | 20% | 4 | 1 (moot — disqualified) | 2 |
| Java 17 + license cleanliness | 15% | 5 (native Java, no Kotlin interop) | 1 (moot — disqualified) | 5 |
| Integration effort | 10% | 4 (segment lexing free; filter-predicate compiler self-written either way) | 1 (moot — disqualified) | 2 |
| **Weighted** | | **4.35** | **1.00 (disqualified)** | **3.55** |

**RECOMMENDED parser: P1 `org.noear:snack4-jsonpath:4.0.59`.** P4 is disqualified (confirmed `internal` AST at the only Maven-Central-published version, `2.4.1`); P2 is disqualified (no Central artifact). P1 clears every hygiene criterion and — verified by direct source inspection, not documentation — provides a genuinely public, walkable segment-level structure that correctly handles the hardest lexing problems. It does **not** eliminate the need to write our own filter-predicate compiler (no library gave us that), but this was always going to be the core of the RFC 9535 → SQL compilation work regardless of parser choice, so P1 meaningfully reduces scope versus P3 (hand-rolled grammar) without being a magic "fully pre-parsed AST" solution. **P3 is no longer a live fallback need** — proceed with P1 directly.

---

## Genuine open choice: SQL translation target

This is a second, independent fork from the parser choice above: once we have an RFC 9535 AST, what does the translator emit?

### Context: the J+G combined-SQL constraint is assumed, not measured

Traced to `docs/requirements/REQ-catalog-discover.md` (present since the repo's initial commit): NFR-1.3 states combined filter+spatial queries must run as a single SQL statement "to avoid two round-trips and Java-side intersection." That is the entire rationale — no benchmark, load test, or perf-review report anywhere in the repo compares it against a two-step (geo-narrow, then filter) alternative. A fallback two-query path is explicitly designed for and deprioritized (NFR-4.4), not ruled out by data. This matters here because it's the reason attribute filtering must compile to **SQL that can run in the same statement as the PostGIS spatial predicate** — if that constraint were ever revisited (e.g. found not to matter under real traffic), a fully in-memory RFC 9535 evaluator over a geo-narrowed candidate set would remove the need for a SQL-targeting translator for attributes entirely. Out of scope to re-litigate here, but worth flagging as a standing assumption the eventual `perf-review` agent could validate independently of this feature.

### Option T1 — Target Postgres's `jsonpath` text dialect (as currently specified above)

Translate the RFC 9535 AST into a Postgres `jsonpath` *string*, bound as a single `?` parameter into `CAST(? AS jsonpath)` (existing `JSONPATH_MATCH`/`BASE_SELECT_WITH_FILTER_RESULT`). This is what the rest of this document currently specifies.

- **Pro:** minimal disruption — reuses `JsonPathQueryBuilder`/`QueryBuilderHelper` exactly as they exist today; the translator is a drop-in replacement for `JsonPathConverter.processFilter()`'s output.
- **Con:** the bound parameter is one opaque string. Postgres's query planner evaluates the whole filter as a single GIN-indexed `@@` match — it never sees the individual literal comparisons as typed SQL predicates, so there's no per-column statistics, no possibility of a targeted expression/functional index on a specific frequently-filtered attribute path, and debugging a slow query means reading jsonpath internals, not a normal `EXPLAIN` plan with visible predicates.
- **Con:** inherits every grammar-reconciliation risk already documented (`.**` depth semantics, `[last]`/negative-index rewriting, quote-escaping) — translation risk lives in reconciling two look-alike path *grammars*.
- Still technically a "prepared statement" today (the `?` is a real bind parameter, not string concatenation — no injection risk), but it's a single opaque blob parameter, not decomposed typed bindings.

### Option T2 — Target plain `jsonb` operators as a typed, multi-parameter WHERE clause

Compile the RFC 9535 AST directly into SQL fragments using Postgres's primitive `jsonb` operators (`->`, `->>`, `#>>`, `@>`, `?`) — e.g. a filter predicate `?(@.price < 100)` becomes `(payload #>> '{price}')::numeric < ?` with `100` bound as its own typed parameter, following the exact `QueryTemplate.condition(clause, params...)` pattern `QueryBuilderHelper` already uses for every *other* filter in this codebase (network, active, validity, schema-context — none of which route through jsonpath text; all of them are typed, individually-bound SQL predicates). Filter selectors on array elements would use `EXISTS` subqueries, mirroring the existing `SPATIAL_EXISTS` pattern.

- **Pro:** genuinely idiomatic prepared statements — each literal is bound with its real type (numeric, text, timestamptz), visible to the planner, enabling per-attribute expression indexes later if a hot path emerges. This is also the pattern every other filter in `QueryBuilderHelper` already follows — T2 is actually the architecturally consistent choice, and T1 (today's design) is the outlier.
- **Pro:** removes dependency on Postgres's own non-standard `jsonpath` dialect entirely. The translation problem changes shape: no more reconciling two competing "jsonpath-flavored" grammars; instead it's "AST → SQL predicate," the same well-trodden problem every ORM/query-DSL (jOOQ, Hibernate Criteria) solves.
- **Con:** materially larger rewrite. `JsonPathQueryBuilder`'s selection-path/condition-mode split and the `exists($ ? (...))` wrapping go away; the translator becomes a proper recursive AST→`QueryTemplate` compiler, closer in shape to `SpatialQueryBuilder` than to today's `JsonPathQueryBuilder`.
- **Con:** RFC 9535's arbitrary-depth/array-traversal expressiveness (wildcard, descendant, nested filters) doesn't flatten trivially into WHERE predicates — each such selector needs its own `EXISTS`/`jsonb_array_elements` subquery shape, not a single flat comparison. This is a *different* hard problem than T1's grammar reconciliation, not obviously a smaller one — likely comparable total effort, shifted from "textual grammar mapping" to "recursive subquery generation."
- **Con:** the denylist boundary (slice-with-step, `count()`, `value()`, unsupported regex) still applies identically — T2 doesn't make any of those four constructs newly expressible; Postgres SQL has no more native concept of "nodelist cardinality" via raw `jsonb` operators than it does via its `jsonpath` type.

### Spike: T1 vs T2 on 4 representative expressions (executed 2026-09-04)

Ran against the local Docker Postgres stack (`discovery-service-postgres`, real `item` table, 2 seeded rows + 1 synthetic row added for the spike and removed afterward). For each case, hand-translated the same RFC 9535 intent into T1 (Postgres `jsonpath` bound via `@@`) and T2 (typed `jsonb`-operator SQL with `EXISTS`/`jsonb_array_elements`), ran both, and diffed the per-row match results. Scratch SQL: `spike_t1_t2.sql` (session scratchpad, not committed — throwaway).

| # | Case | T1 result | T2 result | Finding |
|---|------|-----------|-----------|---------|
| 1 | Flat comparison (`resources[?@.rating.ratingValue >= 4.5]`) | Correct, **after fixing** | Correct | **T1 landmine confirmed**: a bare selection-path bound directly to `@@` (`payload @@ '$.resources[*] ? (...)'`) silently returns false/no-match for every row — no error. `@@` requires the jsonpath to itself resolve to a JSON boolean; a selection path must be wrapped `exists($....)` first. This is exactly why the *existing* `JsonPathQueryBuilder.toPostgresFilter()` already does this wrapping — but it confirms the failure mode is silent-wrong, not loud-error, which is a real hazard for whichever code (translator or hand-written test) forgets it. |
| 2 | Nested array traversal (`offers[*].validity.endDate >= <date>`) | **Wrong** (false negative) | Correct | **T1 landmine, more severe**: comparing via `.datetime()` without an explicit format template fails to parse a `Z`-suffixed ISO-8601 timestamp (`ERROR: datetime format is not recognized`) when queried directly — but *inside* a filter predicate (`?(...)`), the SQL/JSON spec's error-suppression rule makes Postgres silently exclude the candidate instead of erroring. A completely reasonable RFC 9535 date comparison **silently drops legitimate matches with zero visible error** unless the translator gets the datetime format template exactly right. T2's plain `::timestamptz` cast has no equivalent failure mode — standard SQL casting, fails loudly (a cast error) if it fails at all. |
| 3 | Logical `&&` combination | Correct | Correct | No material difference — both straightforward once case 1's `exists()` lesson is applied. |
| 4 | Descendant segment (`$.catalogs.**.resourceAttributes ? (...)`) | Correct, concise (1 line) | Correct, but **required hardcoding the concrete nesting depth** (`catalogs` → `resources`) by hand | **New risk for T2, not previously weighed**: plain `jsonb` operators have no generic recursive-descent primitive. T1's `.**` works at arbitrary, unknown depth by design. A production T2 compiler would need either a hand-generated `WITH RECURSIVE` walk of the jsonb structure (expensive, unindexable, nontrivial to generate correctly from an AST) or would have to fall back to invoking Postgres's own `jsonpath` engine for exactly this construct — which undercuts T2's premise of avoiding that dialect entirely. |

**Revised conclusion — neither target is a clean, unconditional winner:**
- T1 carries **silent-wrong-instead-of-loud-error** risk on two fronts (missing `exists()` wrapping, `.datetime()` format mismatches) — dangerous specifically because it fails quietly. This echoes a risk this codebase has already had to defend against once: the `try_to_timestamptz` exception-safe helper and its surrounding commentary in `QueryBuilderHelper.java:69-114` exist precisely because raw date casts/parses on this kind of data throw or misbehave unpredictably. A T1 translator needs the equivalent discipline applied to `.datetime()` usage, or better, avoid `.datetime()` comparisons in the emitted jsonpath entirely (compare as strings/ISO-lexicographic order where safe, or pre-validate format).
- T2 is **safer within its expressible range** (ordinary SQL failure modes — type errors, not silent false) but **does not generalize to arbitrary-depth descendant search** without either an expensive recursive-SQL generator or falling back to T1's engine for that one construct anyway.

### DECISION — pure T2, no hybrid

**Locked in: T2 (typed, multi-parameter `jsonb`-operator SQL) for the entire RFC 9535 translation surface. No routing to T1/Postgres-`jsonpath` for any RFC 9535 construct, including descendant segments.**

Scoped down the same way T1's original "A" subset was scoped down: comparing against T1's supported set (root, name, wildcard, index/`[last]`/negative-index, filter predicates with `&&`/`||`/`!` and comparisons, descendant `..`), everything carries over to T2 **except the descendant segment**, which has no generalizable equivalent in plain `jsonb` operators (per the spike — it needs either an expensive hand-generated `WITH RECURSIVE` walk or falling back to Postgres's own jsonpath engine, which would defeat going pure-T2). So:

- **Denylist grows by one:** descendant segment (`..`) moves from "supported" to **NACK'd**, alongside slice-with-step, `count()`, `value()`, and unsupported regex — five denylisted constructs total, each with its own error code (see Error handling).
- Everything else T1 supported, T2 supports too — and per the spike, more safely (loud SQL failures instead of T1's silent-false landmines on missing `exists()` wrapping and `.datetime()` format mismatches). Those two T1-specific landmines are now **moot for the RFC 9535 path** — T2 never uses `@@`/`.datetime()` at all.
- The **legacy Postgres-jsonpath fallback path is unaffected by this decision** — it still binds raw Postgres `jsonpath` text natively via `CAST(? AS jsonpath)`/`@@`, exactly as today, because that's literally what legacy clients already send. T1 isn't "chosen or rejected" there — it's just what native Postgres jsonpath already is. Only the **new** RFC 9535 translation target changes.

---

## DESIGN SPEC (for Implement Agent)

### Objective

Make RFC 9535 the wire grammar for `message.intent.filters.expression`. Parse the expression with an RFC 9535 library into an AST; compile the supported subset directly into typed, multi-parameter SQL predicates using plain `jsonb` operators (`->`, `->>`, `#>>`, `@>`, `?`) and `EXISTS`/`jsonb_array_elements` subqueries — fed into `QueryBuilderHelper.QueryTemplate.condition(...)` exactly like every other filter in this codebase (network, active, validity, schema-context); reject the untranslatable subset (including descendant segments) with a NACK; and keep legacy Postgres-jsonpath expressions working, unmodified, via parse-priority auto-detection. Isolate every decision point (translatable? which grammar? which construct is denylisted?) behind clear seams so a future in-memory "B" fallback is additive, not a rewrite.

### Selection/projection semantics — RESOLVED (2026-09-04)

**Verdict: RFC 9535 filters DO need offer-level projection, not just resource-level boolean filtering — this is required, not optional.**

Traced via `PostgreSQLAssembler.mergeOffersFromRow()` (lines 213–230): when `MATCHING_OFFERS_ALIAS` is present and its elements pass `isOfferLike()`, it **populates `catalog.getOffers()` with exactly the projected matched elements** and skips the static-offers fallback — this is load-bearing response-shaping behavior, not incidental. `CatalogPipeline`'s ID-based cross-referencing steps (`filterResourcesByOfferReferences`/`filterOffersByResourceIds`, operating on `resourceIds`/`Resource.getId()`) are independent of this and don't substitute for it.

Crucially, **today's mode-selection heuristic is purely syntactic**: `isSelectionPath()` = "expression starts with `$`" (`JsonPathQueryBuilder.java:113-117`). Since every valid RFC 9535 expression is required by spec to start with the root identifier `$`, **every RFC 9535 filter would trigger projection mode under the existing heuristic** — this isn't an edge case to special-case, it's the default case for the entire feature.

**Implication for the T2 compiler:** `CompiledPredicate` must carry a projection expression alongside the boolean filter — a `jsonb_agg(o) FILTER (WHERE <predicate>)`-shaped SQL fragment producing the matched-offers array, consumed by `PostgreSQLAssembler` exactly like today's `matching_offers` column — whenever the compiled filter targets fields reachable under `offers`. See the revised `CompiledPredicate` shape in "Key interfaces" below.

**Exact trigger condition (must be precise, not left to interpretation):** the projection fragment is set if and only if the AST's first segment after root `$` is a member/name selector matching `offers` (or a wildcard `[*]`/descendant-adjacent form that would include it), mirroring legacy's own scope — legacy's `isOfferLike()` check (`PostgreSQLAssembler.java:255-259`) already only treats a matched element as offer-like when it structurally resembles one (has `offerAttributes`, or lacks `resourceAttributes` but has `resourceIds`), so the compiler's job is to recognize when the *path itself* is anchored under `offers`, not to guess from arbitrary nesting. A filter anchored elsewhere (e.g. `$.resources[?...]`, matching on resource-level fields) produces no projection fragment — `offersProjectionFragment` stays empty, and the query is boolean-`EXISTS`-only, exactly as resource-level legacy filters behave today.

### New package

`org.beckn.discover.service.postgresql.jsonpath.rfc9535`

### Files to create

- `rfc9535/Rfc9535FilterCompiler.java` — **the orchestration seam.** Single entry: `CompiledPredicate compile(String expression)`. Falls back to the legacy Postgres-jsonpath probe (unmodified path, still returns a jsonpath string — see `LegacyCompiledFilter` below) on **both** of the following, not just RFC 9535 parse failure: (a) the expression fails to parse as RFC 9535 at all, **or (b) it parses but hits `UnsupportedConstructException`** (e.g. descendant `..`, which is valid RFC 9535 syntax today's legacy clients may already send and rely on working). Only if the legacy probe *also* fails does the compiler surface the RFC 9535-side NACK (invalid-syntax or construct-specific, whichever applies) — never regress a construct that worked under the old, pre-this-feature behavior. This is what closes the gap the design review flagged: without trying legacy on an `UnsupportedConstructException`, a legacy client using `..` today would start getting NACK'd post-migration, breaking "legacy keeps working unmodified." Classifies the outcome (`RFC_9535` / `LEGACY_POSTGRES` / rejected) for metrics.
- `rfc9535/CompiledPredicate.java` — immutable result for the **RFC 9535** path: `record CompiledPredicate(String whereFragment, List<Object> whereParameters, Optional<String> offersProjectionFragment, List<Object> projectionParameters, GrammarPath grammarPath)` — `whereFragment`/`whereParameters` feed `QueryTemplate.condition(...)` exactly as before; `offersProjectionFragment` (present whenever the compiled filter targets fields reachable under `offers` — see "Selection/projection semantics" above) is a `jsonb_agg(o) FILTER (WHERE ...)`-shaped SELECT-list expression, aliased so `PostgreSQLAssembler.mergeOffersFromRow()` can consume it the same way it consumes today's `MATCHING_OFFERS_ALIAS` column.
- `rfc9535/LegacyCompiledFilter.java` — immutable result for the **legacy** path: `record LegacyCompiledFilter(String postgresJsonpath)` — unchanged shape from what `JsonPathConverter.processFilter()` produces today, since the legacy path is untouched.
- `rfc9535/GrammarPath.java` — `enum { RFC_9535, LEGACY_POSTGRES }` — the metrics/log tag.
- `rfc9535/Rfc9535SqlPredicateCompiler.java` — walks the AST and emits a typed SQL predicate + bound parameters. Handles ONLY: root `$`, name selector, wildcard `*` (via `EXISTS`/`jsonb_array_elements`), index `[n]`/`[last]`/negative-index (via ordinality-filtered `jsonb_array_elements WITH ORDINALITY` or computed offset), filter selector `?(...)` with `&&`/`||`/`!` and `==`,`!=`,`<`,`<=`,`>`,`>=` (typed casts: `::numeric`, `::text`, `::timestamptz` as inferred from the RHS literal). Nested/array-traversal selectors compile to `EXISTS` subqueries mirroring `SpatialQueryBuilder`'s pattern, not flat WHERE predicates. Throws `UnsupportedConstructException` for anything the `UnsupportedConstructDetector` flags.

  **Recursion contract (arbitrary nesting depth).** The 4 spike cases only exercised 1–2 levels by hand; the compiler must generalize to any depth via a single recursive `compile(Segment segment, String correlatedAlias, int depth)` method (operating on `snack4-jsonpath`'s public `Segment` list, plus this compiler's own bracket-content classification per segment — see "Key interfaces" below for the gap between the two):
  - **Alias generation:** each `jsonb_array_elements(...)` call in a generated `EXISTS` gets a depth-qualified alias (`e0`, `e1`, `e2`, ... or a compiler-generated `UUID`-suffixed alias) so nested `EXISTS` blocks never collide, even when the same field name repeats at different depths.
  - **Correlation:** each recursive call receives the enclosing alias to correlate against (e.g. the inner `jsonb_array_elements` call is applied to `e{depth-1}#>'{offers}'`, not back to the outer `payload`), so the generated SQL is a genuinely nested, correctly-scoped `EXISTS` chain — not a flat set of independent subqueries that happen to share a table.
  - **Depth guard:** cap recursion at a fixed max depth (e.g. 8) as a defensive limit against pathological/malicious expressions; exceeding it is treated as a syntax-adjacent rejection (`SCH_INVALID_JSONPATH`), not a stack overflow.
  - This mirrors, structurally, how `SpatialQueryBuilder`'s single-level `EXISTS` pattern would need to compose if it were N levels instead of one — there's no existing N-level precedent in this codebase to copy verbatim, so this contract is new, not reused.
- `rfc9535/UnsupportedConstructDetector.java` — **the denylist seam.** Pure predicate over AST nodes: returns the specific unsupported reason (slice-with-step, `count()`, `value()`, untranslatable `match()`/`search()` regex, **descendant segment**) or empty. Kept separate from the compiler so the future "B" phase swaps "detect → reject" for "detect → route to in-memory eval" with no compiler rewrite.
- `rfc9535/UnsupportedConstructException.java` — carries enum `UnsupportedConstruct { SLICE_WITH_STEP, COUNT_FUNCTION, VALUE_FUNCTION, REGEX_FUNCTION, DESCENDANT_SEGMENT }`, each mapped 1:1 to its own `ErrorCode` (see Error handling) so the NACK can carry both a precise code and a message naming the exact construct.
- `rfc9535/InvalidRfc9535SyntaxException.java` — thrown when the library rejects the expression as not-valid RFC 9535 (distinct from unsupported-but-valid).
- `rfc9535/FilterGrammarMetrics.java` — Micrometer counter `discovr.filter.grammar{path=rfc9535|legacy|rejected, reason=...}`.

### Files to modify

- `service/validation/IntentQueryValidator.java` — replace the "processFilter → Postgres probe" body with a call to `Rfc9535FilterCompiler.compile(expr)`. `Rfc9535FilterCompiler` itself now owns the Caffeine cache (moved from this class — see `JsonPathQueryBuilder` note below for why) keyed on the **raw** expression, caching the compiled result (`CompiledPredicate` / `LegacyCompiledFilter` / negative verdict), not just a boolean. This validator becomes a thin caller: `compile(expr)` succeeds (either grammar) ⇒ ACK path continues; both RFC 9535 AND legacy fail ⇒ NACK `SCH_INVALID_JSONPATH` invalid-syntax message; RFC 9535 hits an `UnsupportedConstructException` **and legacy also fails** ⇒ NACK with the construct-specific code (see Error handling) — but if legacy succeeds for that same expression, no NACK at all (see the corrected fallback order in `Rfc9535FilterCompiler` above). Preserve the transient-vs-non-transient split: the legacy fallback probe still propagates transient DB errors as 5xx (never cache, never 400).
- `service/postgresql/jsonpath/JsonPathQueryBuilder.java` — **materially restructured for the RFC 9535 path.** For `GrammarPath.RFC_9535`, stop calling `jsonPathConverter.processFilter()`/`toPostgresFilter()` entirely — instead take `CompiledPredicate.whereFragment()`/`whereParameters()` and feed them into `QueryBuilderHelper.QueryTemplate.condition(...)`, alongside the existing spatial/network/active/validity/schema conditions; when `offersProjectionFragment()` is present, add it to the SELECT list (new `QueryTemplate` support needed — today's builder only supports the single `BASE_SELECT_WITH_FILTER_RESULT` projection column, hardcoded to the legacy jsonpath shape, so this needs generalizing to accept an arbitrary projection expression). For `GrammarPath.LEGACY_POSTGRES`, keep the existing `toPostgresFilter()`/`exists($ ? (...))`/`JSONPATH_MATCH`/`BASE_SELECT_WITH_FILTER_RESULT` flow completely unchanged. The class now branches on `GrammarPath` at the top rather than having one uniform flow — document this split clearly, since it's a bigger structural change than the original T1-based spec assumed.
- `service/postgresql/QueryBuilderHelper.java` — `QueryTemplate` needs a new method (e.g. `projectionColumn(String alias, String expr, Object... params)`) to support an arbitrary offers-projection SELECT expression alongside `BASE_SELECT`, generalizing what `BASE_SELECT_WITH_FILTER_RESULT` currently hardcodes only for the legacy path.

  **Worked example — J+G combined SQL with an RFC 9535 attribute predicate + spatial predicate + offers projection, all in one statement** (de-risking the "stays intact" claim in "Decided scope," item 2, and satisfying AC test 6 below). **Path arrays and member names below are written as literal `'{price}'`/`'{catalogs,0,offers}'` strings purely for readability — per the C2 hard rule in "Translation mapping table," the actual compiler MUST bind every such path array as a parameterized `?::text[]`, never emit it as literal SQL text; a real compiled query looks like `#>> ?::text[]` with `{"price"}`/`{"catalogs","0","offers"}` passed as bound `String[]` values, not embedded in the SQL string:**
  ```sql
  SELECT i.id, i.catalog_id,
         jsonb_agg(o) FILTER (WHERE (o #>> '{price}')::numeric < ?) AS matching_offers,  -- '{price}' shown literal for readability; compiler binds as ?::text[]
         i.payload AS resource_payload
  FROM item i, jsonb_array_elements(i.payload #> '{catalogs,0,offers}') o                -- '{catalogs,0,offers}' likewise bound, not literal, in the real compiler output
  WHERE EXISTS (SELECT 1 FROM jsonb_array_elements(i.payload #> '{catalogs,0,offers}') o2
                WHERE (o2 #>> '{price}')::numeric < ?)                                   -- WHERE-clause fragment, same RFC9535 predicate
    AND EXISTS (SELECT 1 FROM item_location_collection ilc                               -- unchanged spatial EXISTS
                WHERE ilc.item_id = i.id AND ilc.catalog_id = i.catalog_id
                  AND ST_DWithin(ilc.geom::geography, ST_GeomFromGeoJSON(?::text)::geography, ?))
  GROUP BY i.id, i.catalog_id, i.payload
  ```
  This composes because `QueryTemplate.condition(...)` (for the WHERE-clause `EXISTS`) and the new `projectionColumn(...)` (for the SELECT-list `jsonb_agg`) are independent additions to the same query builder — the spatial `EXISTS` (already existing, untouched) and the RFC 9535 `EXISTS` (new) are just two more `AND`-ed conditions, and the projection column is orthogonal to both. The one real addition versus today's single-row-per-item shape: the projection requires a `GROUP BY` (or a correlated-subquery-based aggregate instead of a join, to avoid needing `GROUP BY` at all — **implement agent should evaluate both and pick whichever composes more simply with the existing `QueryTemplate.build()`**, since introducing `GROUP BY` into a builder that has never needed one before is itself a structural change worth flagging, not assuming away).
- `service/postgresql/jsonpath/JsonPathConverter.java` — **retained, unchanged in behavior**, used ONLY on the `LEGACY_POSTGRES` path (single→double quote, colon-field quoting) — exactly as today, since legacy clients' raw Postgres-jsonpath expressions still need this normalization. The RFC 9535 path never touches this class.
- `controller/DiscoveryController.java` — no structural change. `validateSchema()` still calls `intentQueryValidator.validate()`.
- `service/validation/DiscoveryValidationService.java` — no structural change. The existing absoluteness guard (`expression must start with "$"`, L326-343 — **this file, not `DiscoveryController`**) stays; both grammars require a leading `$`, so it remains a valid cheap pre-check.
- `common/ErrorCodes.java` / `common/ErrorMessages.java` — keep `SCH_INVALID_JSONPATH` for syntax errors; add the five new construct-specific codes (see Error handling).
- `build.gradle` (discover job) — add `org.noear:snack4-jsonpath:4.0.59` (+ `org.noear:snack4` if not pulled transitively) as the pinned RFC 9535 parser dependency.

### Kafka changes

None. Purely a validation/translation change on the request thread + async builder. Topics, producers, consumers untouched.

### DB changes

None required to ship. No tables, columns, indexes, or Flyway migrations. **Worth flagging as a follow-up, not blocking:** the spike found `item.payload` has **no GIN index today** (confirmed via `\d item` against the local stack — only `id`/`catalog_id`/`context_url`/`type`/`offer_ids`/`updated_at` are indexed) — both the legacy jsonpath `@@` path and this feature's new typed predicates currently seq-scan regardless of target. T2's typed predicates are the ones that could actually benefit from a targeted expression/GIN index later (e.g. on a hot attribute path), which T1's opaque jsonpath blob could not — but adding one is out of scope here and should go through `perf-review` once real traffic patterns are known.

### Config properties to add (`DiscoveryProperties` + `application.yml`)

```yaml
discovery:
  filter-grammar:
    # Master switch. When false, skip RFC 9535 parse entirely and behave exactly as today
    # (legacy Postgres probe only) — an instant kill-switch if the parser misbehaves in prod.
    rfc9535-enabled: true
    # When true, the legacy Postgres-jsonpath fallback is attempted after an RFC 9535 parse
    # failure. Set false once the migration audit confirms no app still sends legacy syntax,
    # to remove the ambiguity surface entirely.
    legacy-fallback-enabled: true
```

Both default `true`. No secrets. Bind via `@ConfigurationProperties` (constructor-injected), never `@Value` field injection.

### Key interfaces / method signatures

```java
// Rfc9535FilterCompiler.java
public CompilationResult compile(String expression);
// sealed CompilationResult permits CompiledPredicate, LegacyCompiledFilter
// Tries RFC 9535 first; on InvalidRfc9535SyntaxException OR UnsupportedConstructException,
// falls back to the legacy Postgres-jsonpath probe before giving up.
// throws InvalidRfc9535SyntaxException or UnsupportedConstructException ONLY if the
// legacy fallback probe ALSO fails for the same expression (see Rfc9535FilterCompiler, "Files to create").

// CompiledPredicate.java  (RFC 9535 path)
public record CompiledPredicate(
    String whereFragment, List<Object> whereParameters,
    Optional<String> offersProjectionFragment, List<Object> projectionParameters,
    GrammarPath grammarPath
) {}

// LegacyCompiledFilter.java  (legacy path, unchanged shape)
public record LegacyCompiledFilter(String postgresJsonpath) {}

// UnsupportedConstructDetector.java  (the future-"B" seam)
public Optional<UnsupportedConstruct> firstUnsupported(List<org.noear.snack4.jsonpath.segment.Segment> segments);

// Rfc9535SqlPredicateCompiler.java
public CompiledPredicate toSqlPredicate(List<org.noear.snack4.jsonpath.segment.Segment> segments);   // throws UnsupportedConstructException
```

The AST walked here is `snack4-jsonpath`'s public `JsonPath.getSegments(): List<Segment>` — a real, walkable, public segment list (see "Genuine open choice: RFC 9535 parser library" for exactly what's public vs. not). **Important gap to design around:** the library does NOT expose a structured selector object per bracket segment (index/name/wildcard/slice/filter classification) or a structured filter-predicate tree — only each segment's raw `getOriginalText()`. So `Rfc9535SqlPredicateCompiler` must include its own small, internal classification step (parse a `SelectSegment`'s raw bracket text to determine index/name/wildcard/slice/filter — a first-char dispatch) and its own filter-predicate tokenizer/compiler for `?(...)` content (comparisons, `&&`/`||`/`!`) — this is genuinely new code we own, not something `snack4-jsonpath` hands us, but it's a much smaller, better-scoped task than reimplementing the outer segment-splitting lexer (quotes/escapes/nested-bracket/regex/paren-balancing), which the library does solve correctly and which this compiler must NOT attempt to reimplement. Do not leak `Segment`/`org.noear.snack4.*` types past the `rfc9535` package — `Rfc9535FilterCompiler` returns only `CompiledPredicate`/`LegacyCompiledFilter`.

### Translation mapping table (T2 — the RFC 9535 supported subset)

**Hard rule, no exceptions: every path-segment name and every literal value from the RFC 9535 expression is a bound parameter, never string-concatenated into SQL text — including inside a `text[]` path array like `{name}` in `#>`/`#>>`.** A member name from `['name']`/`.name` is user-controlled input; if it were ever interpolated directly into the SQL text (e.g. building the literal string `'{name}'` by concatenation) rather than bound as a parameterized `text[]` (e.g. `#>> ?::text[]` with a `String[]` parameter, or Postgres's array-literal binding), a hostile member name containing quotes/braces/commas would be a live SQL-injection vector — a direct violation of this repo's own "Parameterized SQL only — no string concatenation" rule. This applies to every row in the table below; the compiler must never build a path array by string formatting.

| RFC 9535 construct | SQL emission (typed, parameterized) | Notes |
|--------------------|--------------------------------------|-------|
| Root `$` | base `payload` reference | identical |
| Name selector `.name` / `['name']` | `payload->?` / `payload #>> ?::text[]` with the name bound as a `?` parameter | name is a bound parameter, never concatenated into the SQL text (see hard rule above) |
| Wildcard `*` / `[*]` | `EXISTS (SELECT 1 FROM jsonb_array_elements(...) e WHERE ...)` | mirrors `SpatialQueryBuilder`'s `EXISTS` pattern |
| Index `[n]` (non-negative) | `payload #> ?::text[]` with the path array (including `n` as text) bound as a `?` parameter | direct positional path, still parameterized — `n` is compiler-controlled but the path array itself must not be built by concatenation |
| Index `[last]`, `[-k]` | `jsonb_array_elements(...) WITH ORDINALITY` filtered on computed offset (`jsonb_array_length(...) - k`) | negative-index arithmetic in SQL, not a Postgres-jsonpath rewrite (see Risks) |
| Filter `?(...)` | `EXISTS (...)` wrapping a typed comparison | |
| Logical `&&` `\|\|` `!` | SQL `AND`/`OR`/`NOT`, grouped with parens | mirrors `schemaFiltersPaired`'s OR-grouping pattern |
| Comparison `== != < <= > >=` | `= != < <= > >=` with an inferred type cast (`::numeric`, `::text`, `::timestamptz`) on the extracted value, value bound as a `?` parameter | typed cast chosen from the RHS literal's shape; the literal itself is always a bound parameter |
| **Descendant `..`** | — | **DENYLIST → NACK** (no generic recursive-descent primitive in plain `jsonb` operators — see spike + DECISION above) |
| **Slice-with-step `[a:b:s]`** | — | **DENYLIST → NACK** |
| **`count()` as count** | — | **DENYLIST → NACK** |
| **`value()`** | — | **DENYLIST → NACK** |
| **`match()` / `search()`** | — | **DENYLIST → NACK** (I-Regexp vs available SQL regex semantics mismatch) |

### Error handling

Six failure classes, each with its own error code. Checked against `~/work/git/protocol-specifications-v2/api/v2.0.0/beckn.yaml:4250-4286` (the `ErrorCode` enum): that document defines the `SCH_*` naming **scheme** and a set of representative codes (including `SCH_INVALID_JSONPATH`) — it is explicitly not, and is not intended to be, an exhaustive enumeration of every error an implementation may need. Beckn implementations are expected to mint additional codes under the documented prefixes as their own error scenarios require. So this feature introduces five new `SCH_*` codes directly in Discovr, following the existing scheme, without needing a change to `protocol-specifications-v2` first or at all:

**Every failure below is only surfaced if the legacy Postgres-jsonpath fallback ALSO fails for the same expression** (see the corrected fallback order in `Rfc9535FilterCompiler`, "Files to create" above) — none of these NACKs fire for an expression that legacy would have accepted, so a construct like `..` that's denylisted under RFC 9535 but valid legacy syntax keeps working via the fallback and never reaches this table.

| Failure | `ErrorCode` | Status |
|---|---|---|
| (a) Invalid RFC 9535 syntax, and not valid legacy syntax either | `SCH_INVALID_JSONPATH` | existing |
| (b) Valid RFC 9535, descendant segment (`..`), and not valid legacy syntax either | `SCH_UNSUPPORTED_JSONPATH_DESCENDANT` | **new** |
| (c) Valid RFC 9535, array slice-with-step (`[a:b:s]`), and not valid legacy syntax either | `SCH_UNSUPPORTED_JSONPATH_SLICE_STEP` | **new** |
| (d) Valid RFC 9535, `count()` function, and not valid legacy syntax either | `SCH_UNSUPPORTED_JSONPATH_COUNT_FUNCTION` | **new** |
| (e) Valid RFC 9535, `value()` function, and not valid legacy syntax either | `SCH_UNSUPPORTED_JSONPATH_VALUE_FUNCTION` | **new** |
| (f) Valid RFC 9535, `match()`/`search()` not translatable, and not valid legacy syntax either | `SCH_UNSUPPORTED_JSONPATH_REGEX_FUNCTION` | **new** |

`UnsupportedConstructException`'s `UnsupportedConstruct` enum maps 1:1 onto codes (b)–(f) — `DESCENDANT_SEGMENT`→`SCH_UNSUPPORTED_JSONPATH_DESCENDANT`, `SLICE_WITH_STEP`→`SCH_UNSUPPORTED_JSONPATH_SLICE_STEP`, etc. — so the NACK carries both a precise, machine-actionable code and a human-readable message, e.g. *"The filter expression uses a JSONPath feature that is not yet supported (descendant segment). Rewrite the expression without it."* **Do not** reflect the raw expression in the response detail (matches current policy — log it, don't echo it).

NACK envelope unchanged (`GlobalExceptionHandler` maps `ErrorResponseException` → `{"message":{"status":"NACK","messageId":"...","error":{"code":"...","message":"..."}}}`). All cases stay HTTP 400 on the sync path, consistent with today's `SCH_INVALID_JSONPATH`.

**No cross-repo dependency.** These five codes are minted locally in Discovr's `ErrorCodes.java`, following the `SCH_*` scheme documented in `protocol-specifications-v2`'s `beckn.yaml`, without requiring any change to that repo — the canonical `ErrorCode` enum is a naming scheme with illustrative examples, not a closed/exhaustive list implementations must extend upstream before use. No spec-repo PR is required to ship this feature. (Worth a lightweight heads-up to whoever maintains `protocol-specifications-v2`, purely so the new codes are discoverable to other implementations that want to align — not a blocking dependency.)

Transient DB errors on the legacy-fallback probe are **never cached and never a 400** — they propagate as 5xx (preserve the current `NonTransientDataAccessException`-only-is-a-400 rule).

### Metrics / logging (migration visibility)

Every processed filter increments `discovr.filter.grammar` tagged `path=rfc9535|legacy|rejected` (+`reason` on rejected). INFO on `legacy` path (a legacy app was seen) via a `LogEvent` constant; DEBUG on `rfc9535`; WARN on `rejected` (existing `VALIDATE_FAILED` pattern). No client-visible tag. Zero client change.

### Migration audit (pre-cutover task, not code)

Collect the real `filters.expression` values sent by the known small set of existing apps and confirm none is **ambiguously valid under both grammars with divergent meaning**. Record sample + verdict in the decision log. This is the mitigation for the accepted bounded ambiguity risk of parse-priority auto-detection.

### Acceptance criteria

- [ ] Chosen parser pinned; coordinates/version/license/last-release recorded; passes hard gate (Java 17, permissive license, walkable AST).
- [ ] Every path-segment name and literal value in the compiled SQL is a bound parameter — verified by a test with a hostile member name (containing `'`, `"`, `{`, `}`, `,`) that would break out of a concatenated path array if injection were possible; the query must still execute correctly (matching the literal member name) rather than error or misbehave.
- [ ] Offers-projection fragment produced whenever the compiled filter targets fields under `offers`, and consumed correctly by `PostgreSQLAssembler.mergeOffersFromRow()` — verified with a committed test fixture (recreate the spike's synthetic shape as a proper test resource, not a reference to the throwaway scratch SQL: one resource with two offers, one `endDate` in the future and one in the past) and an offer-scoped equality/range filter, asserting the legacy `$`-prefixed selection-path projection and the RFC 9535-compiled projection return the identical restricted offer subset for the equivalent filter intent.
- [ ] `QueryTemplate` gains a general projection-column method; `BASE_SELECT_WITH_FILTER_RESULT`'s legacy-only hardcoding is untouched.
- [ ] RFC 9535 supported-subset expressions produce correct, typed, parameterized SQL predicates — verified against real results (result-set equality test), not just SQL-string equality, since the emitted SQL is structurally different from legacy's jsonpath text.
- [ ] Each denylisted construct (including descendant segment) → NACK with its own construct-specific error code and message naming the construct — never runs, never fetch-then-filter.
- [ ] Invalid-under-both-grammars ⇒ NACK `SCH_INVALID_JSONPATH` invalid-syntax message.
- [ ] A legacy expression not valid under RFC 9535 still succeeds via fallback and is tagged `path=legacy`, using the **unmodified** legacy jsonpath flow.
- [ ] `rfc9535-enabled: false` fully restores today's behavior (legacy probe only).
- [ ] Metrics emitted for all three paths; legacy path also logged at INFO.
- [ ] J, J+G, and chain (J+T, J+G+T) paths still build correct combined SQL for supported RFC 9535 filters (new predicate shape, same combinability with spatial/network/active/validity/schema conditions via `QueryTemplate.condition(...)`).
- [ ] Transient DB error during legacy fallback ⇒ 5xx, not 400, not cached.
- [ ] Existing `IntentQueryValidatorTest` + `DiscoveryControllerIntegrationTest` cases pass (legacy fixtures keep working, unmodified).

### Test strategy

**Unit — `Rfc9535SqlPredicateCompilerTest`:** result-correctness assertions per supported construct (run the compiled SQL against fixture rows, assert the right rows match — not string-equality, since T2 has no fixed canonical output shape the way T1's jsonpath text did). Includes a dedicated injection-safety case: a member name containing `'`, `"`, `{`, `}`, `,` compiles to a query that still matches correctly and does not alter the query's structure.
**Unit — `UnsupportedConstructDetectorTest`:** each of the five denylisted constructs (including descendant) → right enum; supported → empty.
**Unit — `Rfc9535FilterCompilerTest`:** valid→`CompiledPredicate` tagged `RFC_9535`; invalid-but-legacy-valid→`LegacyCompiledFilter` tagged `LEGACY_POSTGRES`; neither→`InvalidRfc9535SyntaxException`; unsupported→exception with construct enum; cache reuse verified.
**Unit — `IntentQueryValidatorTest` (extend):** invalid-syntax vs each unsupported-construct NACK (5 cases); transient DB error propagates (Mockito `TransientDataAccessException`).
**Integration — `DiscoveryControllerIntegrationTest` (extend, Testcontainers PG):** (1) RFC 9535 supported filter (flat, nested/array, logical-combo cases from the spike) returns correct catalogs; (2) each of the five denylisted constructs → 400 NACK with construct-named code/message; (3) legacy-only expression still returns results, tagged legacy, unmodified query path; (4) ambiguous-overlap audit cases; (5) `rfc9535-enabled: false` restores legacy-only; (6) J+G combined query with an RFC 9535 attribute predicate + spatial predicate in one statement.

### What NOT to do

- Do **not** implement the in-memory "B" fallback — only leave the `UnsupportedConstructDetector` seam.
- Do **not** silently drop or degrade an unsupported construct — always NACK, descendant segment included.
- Do **not** attempt a generic recursive-descent SQL generator (`WITH RECURSIVE` or similar) to support `..` under T2 — it's explicitly denylisted this pass, not a corner to cut toward.
- Do **not** fall back to Postgres's `jsonpath`/`@@`/`.datetime()` for any RFC 9535 construct, even ones that would be easy to express that way — the whole point of the pure-T2 decision is avoiding that dialect and its silent-failure modes (see spike). If a construct can't be expressed in typed `jsonb`-operator SQL, it's denylisted, not routed to T1.
- Do **not** change the legacy path's `JSONPATH_MATCH`, `BASE_SELECT*`, `exists(...)` wrapping, or `JsonPathConverter` behavior — it stays byte-for-byte as today.
- Do **not** change the J+G combined SQL or the chain (ES→PG) architecture.
- Do **not** add a new request field or client-visible grammar discriminator. `Filter.type` stays dead/unused — do not repurpose it.
- Do **not** invent a new HTTP status — unsupported/invalid stay 400 like today's `SCH_INVALID_JSONPATH`.
- Do **not** use `new ObjectMapper()`, field `@Autowired`, `Thread.sleep` in tests, or hardcoded topic/SQL string concatenation.
- Do **not** build a `text[]` path array (or any SQL fragment) by string-formatting a user-controlled member name into it — every name and literal is a bound `?` parameter, no exceptions (see the hard rule in "Translation mapping table").

---

## Risks / open questions

1. **Offers-projection compiler complexity.** Now that projection is confirmed required (not optional), the T2 compiler must generate correct `jsonb_agg(...) FILTER (WHERE ...)` aggregation logic alongside the boolean predicate for any filter touching `offers` — this is a materially bigger scope than a pure boolean `EXISTS` compiler, and needs its own dedicated test coverage comparing against legacy's `matching_offers` output for equivalent filters.
2. **Negative-index / `[last]` SQL-arithmetic soundness.** RFC 9535 negative indices count from the end; the T2 equivalent computes an offset via `jsonb_array_length(...) - k` against `jsonb_array_elements(...) WITH ORDINALITY`. Concrete verification bar (replaces the vague "provably sound"): a dedicated test matrix covering (a) empty array, (b) single-element array with `[-1]`/`[last]`, (c) `[-k]` where `k` exceeds the array length (must yield "no match," not a SQL error, not a wraparound match), (d) `[last]` on an empty array. All four must produce the RFC 9535-correct result (empty match, no exception) before negative-index support ships; if any case can't be made sound, denylist negative indices this pass rather than ship an off-by-one.
3. **No GIN index on `item.payload` today (spike finding).** Both the legacy jsonpath path and this feature's new typed predicates currently run as seq scans — this predates and is independent of this feature, but T2's typed predicates are the ones that could actually benefit from a targeted index later (unlike T1's opaque jsonpath blob). Not a blocker; flag to `perf-review` once real traffic patterns exist.
4. **Walkable-AST verdict for P1/P4 still pending code-level inspection.** Both candidates cleared license/Java17/Maven-Central/maintenance on 2026-09-04, but neither confirms AST-walkability from documentation — this is the single remaining gate before compiler code can start. Escalate to user if both fail and P3 (own grammar) becomes necessary. (Unaffected by the T1→T2 decision — still need an RFC 9535 AST either way.) **If P3 is needed, budget for it explicitly** — it's a materially bigger scope item than pinning a dependency (hand-writing and testing a spec-conformant parser for the full supported subset), not a same-effort fallback; re-scope/re-estimate the implementation timeline rather than treating "fall back to P3" as a drop-in substitution.
5. **New locally-minted `ErrorCode` values.** The five `SCH_UNSUPPORTED_JSONPATH_*` codes don't appear in `protocol-specifications-v2`'s `beckn.yaml` `ErrorCode` enum (checked at `~/work/git/protocol-specifications-v2/api/v2.0.0/beckn.yaml:4250-4286`) — that's expected, since the enum documents the `SCH_*` scheme with representative examples rather than an exhaustive list. No spec-repo change is required; these codes are added directly to Discovr's `ErrorCodes.java`.
6. **Ambiguity surface of parse-priority auto-detection** — accepted-but-bounded; mitigated by pre-cutover audit + `legacy-fallback-enabled` off-switch once migration done. Unaffected by the T1→T2 decision (legacy path is unchanged).
7. **Type-cast inference for comparisons.** T2 infers `::numeric`/`::text`/`::timestamptz` from the RHS literal's shape in the RFC 9535 expression. Get this wrong (e.g. a numeric-looking string meant to stay text) and the predicate either errors loudly (good — matches the pure-T2 "fail loud" rationale) or silently miscasts. Needs explicit test coverage per type, not just the happy path.
8. **Offers-projection may require `GROUP BY`, which `QueryTemplate` has never needed before.** Surfaced by the J+G worked example (see "Files to modify" above): projecting `jsonb_agg(...)` alongside a per-item row shape needs either a `GROUP BY i.id, i.catalog_id, i.payload` or restructuring the projection as a correlated scalar subquery instead of a joined aggregate. This is a real structural addition to `QueryTemplate.build()`, not a detail to assume away — the implement agent should evaluate both approaches and record the choice, since introducing `GROUP BY` changes the shape of every query `QueryTemplate` builds, not just RFC 9535 ones with a projection.
9. **N+1 / per-row subquery cost at scale.** The recursion contract (nested nested `EXISTS`/`jsonb_array_elements` per depth level) means a deeply-nested or multiply-filtered RFC 9535 expression compiles to correspondingly nested subqueries evaluated per candidate row. Combined with risk 3 (no GIN index on `payload` today), this could be materially slower than legacy's single opaque `@@` jsonpath match for pathological expressions, even though it's safer/more correct. Not a blocker for this pass (no perf regression claim is being made either way — legacy is already unindexed), but flag to `perf-review` alongside risk 3 rather than assuming T2's typed predicates are free of their own cost concerns just because they're safer.

---

## Parser decision log

**Pre-vetted 2026-09-04** (license/Java17/Maven-Central/maintenance — see "Genuine open choice: RFC 9535 parser library" above). First code-level AST check (2026-09-07) incorrectly disqualified P1 on a Maven Central false negative and correctly disqualified P4; that P1 finding was **corrected 2026-09-07 (same day, follow-up check)** after the user asked to re-verify against `repo1.maven.org` directly rather than `search.maven.org`'s Solr search, which was stale/unreliable.

**P1 `org.noear:snack4-jsonpath:4.0.59` — VIABLE. Corrected finding, supersedes the initial "DISQUALIFIED" entry.** The initial check queried only `search.maven.org`'s Solr search API, which returned zero results for `g:org.noear` — this was a false negative in that search index, not the actual repository state. Re-verified directly against `repo1.maven.org/maven2/org/noear/snack4-jsonpath/maven-metadata.xml`: the artifact is real, actively published, latest `4.0.59`. Downloaded the jar + sources jar from `repo1.maven.org` and read the actual source:
- `JsonPathParser.parse(path): JsonPath` and `JsonPath.getSegments(): List<Segment>` are both **public**, giving a real, walkable, ordered list of the expression's segments. Concrete segment types (`SelectSegment`, `FuncSegment`, `DescendantSegment`) are public classes; each exposes `getOriginalText()` (raw source text of that segment) and is distinguishable via `instanceof`.
- The library's own segment-splitting logic (verified by reading `JsonPathParser.parseSegment()` in full) correctly handles nested brackets, quotes, escapes, regex literals, and balanced parens/braces inside function-call args — the genuinely hard part of lexing a JSONPath-family expression — and this is reachable through the public API above.
- **The real gap:** `SelectSegment`'s internal `List<Selector> selectors` field is `private`, with no getter — so a bracket segment's selector kind (index/name/wildcard/slice/filter) isn't retrievable as a structured object, only as raw text via `getOriginalText()`. Similarly, `Expression` (`org.noear.snack4.jsonpath.filter.Expression`, the filter-predicate evaluator) stores its parsed token list in a `private final List<Token> rpn` with package-private `Token` fields — filter-predicate internals (comparisons, `&&`/`||`/`!` structure) are eval-only, not walkable.
- **Net verdict:** a genuine, partial walkable AST — segment-level structure is real and public; selector- and filter-predicate-level structure is not, and must be written by us on top of each segment's raw text (see "Key interfaces" above for what that entails). This is meaningfully less work than a from-scratch grammar (P3), since the hardest lexing problem is solved and exposed; it is not a fully pre-parsed AST either.

**P4 `at.asitplus:jsonpath4k-jvm` 2.4.1 — DISQUALIFIED, confirmed.** Downloaded the artifact + `-sources.jar` from Maven Central and inspected the Kotlin source directly (not docs/Javadoc): `JsonPath`'s compiled query is a `private val` with no accessor, and the actual RFC 9535 semantic model (`JsonPathSelector` sealed interface: `RootSelector`, `MemberSelector`, `WildCardSelector`, `IndexSelector`, `SliceSelector`, `DescendantSelector`, `FilterSelector`, ...) is declared `internal` — not part of the module's public API, unreachable from Java. Cross-checked against the exact `2.4.1` git tag (the only version ever published to Maven Central — confirmed via `repo1.maven.org/maven2/at/asitplus/jsonpath4k-jvm/maven-metadata.xml`, `versionCount: 4`, latest `2.4.1`) to rule out a version-mismatch false conclusion: the `internal` modifier is present at that exact tag. (A later, unpublished GitHub development state — tags up to `4.0.0` — has since made this class public, but that state was never released to Maven Central, so it isn't usable as a real dependency; do not be misled by browsing the GitHub default branch without pinning a version, which was the mistake this cross-check exists to catch.) `FilterSelector.filterPredicate` is additionally `private`. The nominally-public ANTLR-Kotlin-generated grammar underneath is an undocumented implementation artifact, not a supported contract, and consuming it is not materially different in effort from writing an entirely new parser.

**Final verdict: P1 is viable and recommended; P3 is not needed.** No compiler code had been written pending this correction, per the doc's sequencing requirement; `implement` should proceed with P1 now.

- Pinned parser + exact version: **`org.noear:snack4-jsonpath:4.0.59`** (+ `org.noear:snack4` transitively or explicitly).
- Walkable AST confirmed: **Partially — segment-level: yes (public `getSegments()`); selector/filter-predicate-level: no (private/package-private internals)**. See P1 entry above for the exact boundary and what the compiler must write itself as a result.
- Negative-index SQL-arithmetic soundness verdict: **sound.** Implemented as an ordinality-filtered
  existence check (`jsonb_array_elements(...) WITH ORDINALITY e0(value, ord) WHERE e0.ord - 1 = ...`)
  with the offset computed via `jsonb_array_length(...) + (negativeIndex)` — never a wraparound or a
  hardcoded assumption about array length. An empty array, a single-element array with `[-1]`/
  `[last]`, an out-of-range negative index, and `[last]` on an empty array all naturally evaluate to
  "no matching ordinal" (the `EXISTS` is false) rather than a SQL error or a false match, because
  Postgres's `jsonb_array_elements` simply returns zero rows for an empty/absent array. Covered by
  `Rfc9535SqlPredicateCompilerTest#negativeAndLastIndex_useComputedOffset`.
- Offers-projection compiler verdict: **implemented as a correlated scalar subquery, not a
  `GROUP BY`** — see the "GROUP BY vs. correlated-subquery" entry immediately below.
- **GROUP BY vs. correlated-subquery for the offers-projection column (risk 8) — RESOLVED: correlated
  subquery, no `GROUP BY`.** Evaluated both against the real `QueryBuilderHelper.java`:
  - The `GROUP BY` shape (a `FROM item i, jsonb_array_elements(...) o` join + `jsonb_agg(o) FILTER
    (...)` + `GROUP BY i.id, i.catalog_id, i.payload`) would require `QueryTemplate.build()` to grow a
    `GROUP BY` clause for the first time ever, and every *other* filter/spatial condition added via
    `.condition(...)` would then implicitly need to be `GROUP BY`-compatible (aggregate-safe) even
    though none of them currently are — a structural change with a large, indirect blast radius
    across every query `QueryTemplate` builds, not just RFC 9535 ones with a projection.
  - The correlated-subquery shape — `(SELECT jsonb_agg(po) FROM jsonb_array_elements(i.payload #>
    ?::text[]) po WHERE <predicate>)` as a single SELECT-list expression, correlated to the outer
    `i` — needs no `GROUP BY`, no join, and no change to how any other condition composes. It slots in
    exactly like `QueryBuilderHelper.BASE_SELECT_WITH_FILTER_RESULT`'s existing
    `jsonb_path_query_array(...)` column already does (also a self-contained SELECT-list expression,
    no join/`GROUP BY`), so it's the option that's actually consistent with this codebase's existing
    pattern, not just the smaller diff.
  - **Decision: correlated scalar subquery.** Implemented in `Rfc9535SqlPredicateCompiler
    .compileFilterTerminal()`; `QueryBuilderHelper.QueryTemplate` gained a new
    `projectionColumn(alias, expr, params...)` method (generalizing `BASE_SELECT_WITH_FILTER_RESULT`'s
    hardcoded legacy-only column) but did **not** grow a `GROUP BY` capability — risk 8 is closed
    without introducing that structural change.
- Migration audit sample + ambiguity verdict: not run — this requires collecting real
  `filters.expression` traffic from existing BAP apps, which is outside a code-change PR's scope.
  Tracked as the pre-cutover task already called out in "Migration audit (pre-cutover task, not
  code)"; unblocked now that expressions can be compiled (`Rfc9535FilterCompiler.compile(...)`).
- **Arbitrary-nesting-depth chaining (the recursion contract above) — RESOLVED, not merely
  narrowed.** The first implementation pass shipped with the recursion contract narrowed to a
  single terminal selector: any wildcard/filter/slice/index/`last` followed by further segments
  was rejected (`InvalidRfc9535SyntaxException`, "A traversal after a filter/wildcard/index/slice
  terminal is not supported"). This blocked `$.resources[*].offers[?(@.price < 100)]` — the
  primary real-world shape (Discovr's payload is `catalogs[0].resources[*]`, each resource
  carrying its own `offers`), and is literally the shape the T1-vs-T2 spike's own Case 2 used to
  validate the whole T2 approach, so the compiler could not reproduce its own validating example.
  **Fixed in a follow-up pass:** `Rfc9535SqlPredicateCompiler` now implements the full recursion
  contract as originally specified — every array-producing selector (wildcard, filter, plain
  slice, index/`last`/negative-index) that is followed by further segments opens a correlated
  `jsonb_array_elements(...)` level (`e0`, `e1`, `e2`, ...), and the remaining segments compile
  against that level's element, recursively, at unbounded depth (still capped by
  `MAX_PATH_DEPTH`, now counting every processed segment — flat path components and nested levels
  alike — so a pathological expression is rejected, not turned into unbounded SQL). All opened
  levels are emitted as sibling `FROM`-list items inside a single `EXISTS(...)` (semantically
  equivalent to, and simpler than, a chain of nested `EXISTS` blocks, since every level is an
  existential/narrowing quantifier ANDed together) — verified against real query results, not
  just SQL-string shape, including a 3-level-deep case
  (`catalogs[*].resources[*].offers[?(...)]`), by
  `Rfc9535SqlPredicateCompilerNestedIntegrationTest`. The offers-projection trigger
  (`OFFERS_PATH_COMPONENT` check) generalizes for free under this design: "offers" is detected
  whenever it appears as a name component at *any* recursion level, not just the outermost flat
  path list, because each level's accumulated path components are checked as they're added. No
  chaining shape within the supported selector set (name/index/wildcard/filter/slice) is
  unsupported after this fix; the denylist (descendant segment, slice-with-step, `count()`,
  `value()`, `match()`/`search()`) is unchanged and still rejects via
  `UnsupportedConstructException` with its dedicated `SCH_UNSUPPORTED_JSONPATH_*` code, never via
  `InvalidRfc9535SyntaxException`.
