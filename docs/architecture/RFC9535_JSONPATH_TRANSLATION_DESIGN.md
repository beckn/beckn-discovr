# Design: RFC 9535 JSONPath Filtering with Pluggable Database Translation

**Status:** Under Review
**Service:** `catalog-discover-job` (Beckn Discovr)
**Date:** 2026-06-30

---

## 1. Problem Statement

### How filtering works today

Clients filter catalog resources by sending a JSONPath-like string in
`message.intent.filters.expression`. Discovr doesn't understand this string itself. It just
hands it to the database as-is:

- **Validation:** ask the database if it can parse the string. If yes, ACK. If no, NACK.
- **Execution:** run the same string against the database, unchanged.

So the database decides what's valid, and the database's own path language is the only
thing that ever runs the filter. Discovr has no independent understanding of the filter
itself. This causes two problems:

1. **Clients must write the database's dialect, not real JSONPath.** Today's database has
   its own path language (SQL:2016), which looks like RFC 9535 but isn't. RFC 9535 is the
   actual JSONPath standard. A filter written correctly in RFC 9535 gets rejected today, and
   vice versa (see §3 for examples).
2. **The system is locked into one database.** Validation and execution both run on the
   exact same database-specific string. Switching to a different database, or adding a
   second one, would mean rebuilding filtering from scratch, breaking every existing client
   filter.

---

## 2. Goals

1. **Let clients use real RFC 9535 JSONPath**, not any one database's dialect.
2. **Keep backward compatibility.** Filters already written in the current database's
   dialect must keep working.

---

## 3. Design

### High-level flow

![RFC 9535 JSONPath filtering flow](assets/rfc9535-jsonpath-flow.png)

The client's raw filter (RFC 9535) flows left to right through a **Validator**, then a
**Translator**, then an **Executor**, which runs the translated query against the
**Database**. A filter the Validator can't parse, or the Translator can't convert, is
rejected with a NACK instead of moving forward. Each stage is explained below.

### Validator

- **What it takes:** the raw filter string the client sent.
- **What it checks, in order, before the request is even acknowledged:**
  - Is it valid RFC 9535? A syntax check against the RFC 9535 grammar.
  - Can the current database actually run it? Some valid RFC 9535 filters still have no way
    to run on the current database (see §4). Checking this means attempting the translation
    itself, so this check and the Translator are the same piece of work.
  - Does the database actually accept the translated query? One last live check against the
    real database, to catch anything the first two checks missed.
- **What it produces:** if all three checks pass, the request is acknowledged (ACK) and
  queued. If any check fails, the request is rejected (NACK).
- **What it's built with, and why:** the first check uses **ANTLR4**, a widely used parser
  generator. Given a formal grammar for RFC 9535, it generates a parser automatically,
  instead of one being hand-written from scratch. Hand-writing a parser for a grammar this
  size is slow to build and easy to get subtly wrong; ANTLR4 is a standard, well-tested tool
  for exactly this problem.
- **What it doesn't tell you:** the first check only confirms *format*, that is, whether the
  string is valid RFC 9535 syntax. It says nothing about whether the database can run it.
  That's what the second and third checks are for.

### Translator

- **What it takes:** a filter already confirmed to be valid RFC 9535 syntax, and which
  database it needs to run on.
- **What it produces:** an equivalent query string, written in that database's own query
  language.
- **Example:**
  - Input: `$.resources[?(@.category == 'BEVERAGES')]`
  - Output (today's target database): `$.resources ?(@.category == "BEVERAGES")`
- **How it decides what to emit:** the translator is written to be pluggable per database.
  It knows which database it's currently targeting, and emits that database's syntax for
  each piece of the filter. Supporting a second database later means adding a second
  translation path, not rewriting this one.
- **What it's built with, and why:** a search for an existing library that already does
  "read RFC 9535, output a query in another language" turned up nothing. Every RFC 9535
  library found only evaluates the filter in memory against JSON already loaded; it doesn't
  emit another query language. So the translator is hand-built, walking the same structure
  the Validator's parser produces, using the **visitor pattern**, a standard way of walking
  a tree-shaped structure and doing something at each piece.
- **A few examples of how the syntax differs:**

  | Feature | RFC 9535 | Today's target database |
  | :--- | :--- | :--- |
  | Filter selector | `[?(@.price < 10)]` | `? (@.price < 10)` |
  | String quotes | single or double | double only |
  | Existence check | `[?(@.details)]` | `exists(@.details)` |
  | Regex | `match(@.name, 'regex')` | `@.name like_regex "regex"` |
  | Array index | `[0]`, `[-1]` | `[0]`, `[last]` |

- **What it can't translate:** some valid RFC 9535 filters have no equivalent at all on the
  current database (e.g. recursive descent, `count()`, negative slice steps; see §4 for the
  full list).
- **What happens then:** the translator never guesses at a partial translation. If a filter
  can't be faithfully translated, the request is rejected with a NACK saying the expression
  isn't supported, instead of running something that might quietly give the wrong answer.

### Executor

- **What it takes:** the translated query string from the Translator, exactly as it was
  validated. It's never re-translated.
- **What it does:** sends the query to the database and runs it, returning the matching
  resources.
- **How it connects:** the translated string is passed to the database as a **bind
  parameter**, never pasted directly into the query text. The database always treats it as a
  value to match against, never as something to execute. This is what keeps the filter safe
  to run even though it's fully client-supplied.
- **Why nothing changes between checking and running:** the Executor reuses the exact query
  the Validator already proved works, so a filter can't behave differently at execution time
  than it did during validation.

---

## 4. Challenges with the Proposed Design

### Some RFC 9535 features just don't map to the current database

| RFC 9535 feature | Why the current database can't do it |
| :--- | :--- |
| Recursive descent (`..`) | The closest match gives duplicates in a different order, which is the wrong result. |
| Wildcard on unknown type (`.*`) | RFC's wildcard works on objects and arrays; the database's only works on objects. |
| Filtering the root itself (`$[?…]`, `$[0]`) | The database treats the root as one value, not a list to iterate. |
| Comparing two paths (`@.a == @.b`) | RFC's equality rules here aren't reproducible on this database. |
| Existence check on multiple matches (`@.tags[*]`) | The existence check behaves differently once more than one match is possible. |
| `count()` | RFC counts nodes matched. The closest function counts array length, which isn't the same thing. |
| Slice with a step, or negative step (`[::2]`, `[::-1]`) | Only a plain, continuous range is supported. |
| A few regex features (e.g. Unicode classes) | The regex engine doesn't support them. |

These are mostly edge cases. Normal Beckn filters (equality, ranges, AND/OR, checking
fields across catalogs, resources, and offers) all work fine.

### If it can't be translated, it's rejected, never guessed

When a filter can't be faithfully translated, the service sends a clear NACK instead of
trying a partial or "close enough" translation. A client either gets a correct result, or a
clear "not supported." Never a silently wrong answer.

An alternative was considered: filtering results in application code instead of in the
database, for the cases the database can't handle. This was ruled out because the filter
usually narrows the result set the most. Skipping it in the database query would mean
pulling a huge, unfiltered result set into memory first, which isn't practical at scale.

### Tested against the official spec, not just claimed

The translated queries are run against the official JSONPath Compliance Test Suite and
compared to the expected output:

- Everything currently accepted and run gives the exact right answer. No wrong results.
- **Known gap:** a small number of inputs that the spec says are invalid are currently
  accepted instead of rejected (the parser is a bit too lenient in some edge cases). This
  doesn't cause wrong answers on valid queries, but it's a gap that should be closed.

---

## 5. Benefits of the Proposed Design

- **No lock-in to any one database.** The client-facing side doesn't know which database is
  behind it, and all database-specific logic is behind one swappable piece. Supporting
  another database later means writing one new translation path, not a rewrite.
- **Bad filters fail fast.** Clients get a clear NACK immediately, instead of the request
  silently failing later in async processing.
- **RFC 9535 compliance can be proven, not just claimed.** Because it uses a real grammar
  instead of ad hoc string matching, it can be run against the official compliance test
  suite and the results shown.
