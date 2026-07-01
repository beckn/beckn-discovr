# RFC 9535 JSONPath Translation — Pending Issues

**Service:** `catalog-discover-job` (Beckn Discovr)
**Status:** Tracked, not yet fixed
**Related:** `RFC9535_JSONPATH_TRANSLATION_DESIGN.md`, `RFC9535_TRANSLATION_TECHNICAL_DESIGN.md`
**Date:** 2026-07-01

---

## Context

`message.intent.filters.expression` is a client-supplied RFC 9535 JSONPath string.
It is parsed by an ANTLR4 grammar (`JsonPath.g4`), translated to PostgreSQL
SQL/JSON path (`PgJsonPathEmitter` / `Rfc9535PgTranslator`), and validated
**synchronously on the API request thread** (`IntentQueryValidator`) — including
a live PostgreSQL pre-flight probe — before the request is ACKed and queued to
Kafka. Because this string is fully attacker/partner-controlled and reaches a
live database connection on the synchronous path, it was reviewed specifically
for production-safety against adversarial input (not just RFC compliance).

**What is already confirmed safe:**
- **SQL injection:** the translated JSONPath is always passed as a JDBC bind
  parameter (`CAST(? AS jsonpath)`) — verified across `IntentQueryValidator`,
  `QueryBuilderHelper`, and `JsonPathQueryBuilder`. No path concatenates user
  input into SQL text.
- **Correctness on the supported subset:** 69/69 executed CTS cases match the
  spec exactly (0 mismatches); the 524-scenario result-validation suite passes
  100%.

Everything below is a **gap**, not yet fixed.

---

## Issues

### 1. Unbounded recursion → uncaught `StackOverflowError` (High)

- **What:** The grammar's filter expressions (`parenExpr`, `logicalOr`/`logicalAnd`
  chains) parse via Java-stack recursion in ANTLR's generated parser/visitor,
  with no depth guard. A string like `$[?(((((...)))))]` with a few thousand
  nested parens exhausts the JVM stack.
- **Evidence:** `JsonPath.g4:54-61` (mutually recursive `logicalOr`/`logicalAnd`/
  `basicExpr`/`parenExpr`); `Rfc9535PgTranslator.compile()` only catches
  `ParseCancellationException` and, separately,
  `FilterParseException | UnsupportedFilterException | RuntimeException` around
  the emitter walk. `StackOverflowError extends Error`, not `Exception`, so it
  is not caught there or by `GlobalExceptionHandler`'s `@ExceptionHandler({ Exception.class })`.
- **Impact:** A single crafted request can throw an uncaught `Error` on the API
  request thread (or the async-authorization executor thread on the POST path),
  with no clean Beckn NACK produced — behavior becomes container/JVM-dependent
  rather than deliberately handled.
- **Fix direction:** Add a nesting-depth guard (ANTLR `Parser.setErrorHandler` /
  a custom `ParseTreeWalker` depth counter, or cap on token count as a proxy),
  and/or catch `StackOverflowError` explicitly at the translation boundary and
  convert it to `UnsupportedFilterException` → NACK.

### 2. No query/statement timeout on the regex path (High)

- **What:** RFC `match()`/`search()` map to Postgres `like_regex` with no
  complexity or length check on the pattern, and no `statement_timeout` is
  configured anywhere in the datasource config or on the probe call itself.
- **Evidence:** `PgJsonPathEmitter.visitFunctionExpr` (regex branch) only
  rejects Unicode-property syntax (`\p{...}`) because PG can't parse it — not
  for complexity. `application.yml` datasource block has no
  `statement_timeout` GUC; `discovery.postgresql.connection-timeout` is
  HikariCP's pool-acquire timeout, not a query timeout;
  `discovery.postgresql.parallel-query-timeout-seconds` is only applied to the
  *async* consumer's query execution (`DiscoveryService`), not to
  `IntentQueryValidator.probe()`.
- **Impact:** A pathological regex can block a Postgres connection
  indefinitely, including during the **synchronous** validation probe on the
  API request thread.
- **Fix direction:** Set a `statement_timeout` on the probe connection (or via
  `SET LOCAL statement_timeout` before the `CAST(? AS jsonpath)` call), and
  consider a regex length/complexity cap in the emitter.

### 3. Cache-flood → connection-pool exhaustion (High)

- **What:** The live-PG-probe cache (`IntentQueryValidator.validityCache`) and
  the translator's own cache (`Rfc9535PgTranslator`) are Caffeine
  `maximumSize(10_000)` with no TTL, keyed on the (processed / trimmed)
  expression string. Repeats of the *same* invalid expression are cheap
  (cached), but a flood of **distinct** expressions (trivial to generate — vary
  one literal per request) causes a cache miss and a live PG round-trip every
  time.
- **Evidence:** `IntentQueryValidator.java` cache declaration and `probe()`;
  `Rfc9535PgTranslator` cache keyed on `rfc9535Expression.trim()`. No rate
  limiting or request-budget dependency exists anywhere in `main/` (no
  bucket4j or equivalent).
- **Impact:** Direct path to exhausting the HikariCP connection pool under a
  flood of unique filter strings — a request-rate DoS, not just a memory-bound
  one (cache size only bounds memory, not PG round-trip rate).
- **Fix direction:** Add per-client or global rate limiting on the validation
  path, and/or a max-distinct-expressions-per-window budget before the probe.

### 4. No input length cap (Medium — enables #1–#3)

- **What:** `filters.expression` has no `maxLength` anywhere in the chain:
  not in schema validation, not in `DiscoveryValidationService` (which only
  checks blank / must-start-with-`$`), not in `IntentQueryValidator`, not in
  `Tomcat`/`server.*` config (`application.yml` only tunes thread pool /
  connection counts / compression, no `max-http-request-size` override).
- **Impact:** Removes the cheapest mitigation for #1–#3 — a length cap alone
  would sharply bound worst-case nesting depth and regex pattern size.
- **Fix direction:** Add an explicit `maxLength` check on the raw expression
  string before it reaches the ANTLR parser (reject early with
  `SCH_INVALID_JSONPATH`).

### 5. No test coverage for any of the above (Medium)

- **What:** No test in `src/test/` (searched for malicious / dos / overflow /
  injection / large / deep-nest / timeout / stress / fuzz) exercises deep
  parser nesting, oversized input, adversarial regex, or high-cardinality
  cache-flood behavior. `IntentQueryValidatorTest` only checks same-expression
  cache-hit behavior (`times(1)` probe call), not distinct-expression load.
- **Fix direction:** Add adversarial test cases alongside the fixes above
  (e.g. a parametrized nesting-depth test asserting a clean NACK, not a crash).

---

## Known functional gap (separate from the above — not a security issue)

### 6. Over-lenient grammar accepts 90/247 CTS invalid-per-spec selectors (Medium)

- **What:** Per the CTS compliance run, 90 of 247 invalid-per-spec selectors
  are currently **accepted** rather than rejected (e.g. malformed
  root/whitespace variants, embedded control characters in quoted member
  names) — over-lenient parsing, not a mistranslation of a valid query.
- **Evidence:** `CtsComplianceIT` run results (2026-06-30); already documented
  in `RFC9535_JSONPATH_TRANSLATION_DESIGN.md` §7.
- **Impact:** Compliance claim should be read as "supported subset is 100%
  correct," not "input validation is airtight" — some spec-invalid input
  currently gets processed instead of rejected.
- **Fix direction:** Tighten grammar/lexer rules to reject the 90 known cases;
  see `CtsComplianceIT` for the enumerated list.

---

## Recommendation

Safe to expose today to **trusted internal callers** or behind infra-level
protections (WAF/gateway body-size limit, rate limiting). **Not safe to
expose directly to arbitrary/adversarial BAPs** on the open Beckn network
without at minimum fixing #1–#4 (length cap, recursion guard, statement
timeout, rate/budget limit on probe misses).
