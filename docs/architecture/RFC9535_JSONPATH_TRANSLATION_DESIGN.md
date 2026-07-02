# Design: RFC 9535 JSONPath Filtering with Pluggable Database Translation

**Status:** Under Review
**Service:** `catalog-discover-job` (Beckn Discovr)
**Date:** 2026-07-02

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
   dialect must keep working. The request itself declares which dialect it's using, in
   `message.intent.filters.type`: `rfc9535` for the standard path described in this
   design, and the existing legacy value for filters written the old way. Legacy filters
   skip everything below and run exactly as they do today, so nothing changes for
   existing clients, and there's never any guessing about which dialect a filter is in.

---

## 3. Design

### High-level flow

![RFC 9535 JSONPath filtering flow](assets/rfc9535-jsonpath-flow.png)

The client's raw filter (RFC 9535) flows through a **Validator**, then a **Translator**,
then an **Executor**, which runs the translated query against the **Database**. The
Validator alone decides ACK or NACK, before the request is queued; the Translator and
Executor run later, asynchronously, and only ever see filters that already passed. A
filter that isn't valid RFC 9535, or that uses a feature the current database can't run,
is rejected with a NACK instead of moving forward. Each stage is explained below.

### The parsed filter tree

One idea ties the whole design together: as soon as the filter parses, it stops being a
string and becomes a small structured tree, with one node per piece of the filter. The
manufacturer example used throughout this document breaks down like this:

```
Filter
 ├─ path:      catalogs[*] → resources[*]
 └─ condition: EQUALS
      ├─ left:  @.resourceAttributes.packagedGoodsDeclaration.manufacturerOrPacker.name
      └─ right: "Hindustan Unilever Limited"
```

Every stage after parsing works on this tree, never on the raw string. The Validator
checks the tree, node by node, against a capability table. The Translator compiles the
tree into the target database's own query form. Nothing database-specific exists until
the last possible moment, which is what makes the database swappable.

### Validator

- **What it takes:** the raw filter string the client sent.
- **What it checks, in order, before the request is even acknowledged:**
  - Is it valid RFC 9535? A syntax check against the RFC 9535 grammar, which also
    produces the parsed filter tree used by everything downstream.
  - Can the current database actually run it? Each node of the tree is looked up in a
    **capability table**: a plain list, kept per database, of which RFC 9535 features
    that database supports. The unsupported-features list in §4 is exactly the reject
    side of this table for the current database.
- **What it deliberately does not do: talk to the database.** Deciding ACK or NACK is a
  parse plus a table lookup, nothing more. No query is sent anywhere. This matters for
  two reasons:
  - The request thread never waits on a database round-trip during validation, so a flood
    of never-seen-before filter strings can't tie up database connections before requests
    are even accepted.
  - Requests can still be correctly accepted or rejected while the database is briefly
    unavailable, which fits how the service already works: accept now, process later.
- **Why the Validator decides everything up front:** discover requests are ACKed
  immediately and processed later, asynchronously. Once that async processing starts,
  there's no way back to send a NACK. So everything that could reject a request has to be
  decided before the ACK, and a table lookup makes that decision instant.
- **What it produces:** if both checks pass, the request is acknowledged (ACK) and
  queued, carrying the client's original filter string untouched. If either check fails,
  the request is rejected (NACK) with a reason that tells the client which check failed:
  the filter isn't valid RFC 9535, or it's valid but uses a feature the current database
  doesn't support.
- **What it's built with, and why:** the syntax check uses **ANTLR4**, a widely used
  parser generator. Given a formal grammar for RFC 9535, it generates a parser
  automatically, instead of one being hand-written from scratch. Hand-writing a parser
  for a grammar this size is slow to build and easy to get subtly wrong; ANTLR4 is a
  standard, well-tested tool for exactly this problem.
- **What the syntax check alone doesn't tell you:** only that the string is
  well-formed RFC 9535. Whether the current database can run it is a separate question,
  and that's what the capability table answers.

### Translator

- **When it runs:** asynchronously, after the ACK, when the queued request is picked up
  for processing. By then the filter has already passed both validation checks, so the
  Translator never sees a filter it can't handle.
- **What it takes:** the parsed filter tree, and which database the query needs to run
  on. The filter is parsed again from the queued request using the same grammar; parsing
  is cheap and always produces the same tree, so the Translator works on exactly what
  the Validator approved.
- **What it produces:** that database's own **native query form**. For today's database,
  that's a query string in its path language. For a different kind of database it can be
  something else entirely; a search engine, for example, takes a structured query object,
  not a path string. The contract is "whatever that database natively runs", never
  "a string", because not every database has a string path language to target.
- **Example:** a real Discovr filter, matching resources from a specific manufacturer.
  - Input (RFC 9535): `$.catalogs[*].resources[?(@.resourceAttributes.packagedGoodsDeclaration.manufacturerOrPacker.name == 'Hindustan Unilever Limited')]`
  - Output (today's target database): `$.catalogs[*].resources[*] ? (@.resourceAttributes.packagedGoodsDeclaration.manufacturerOrPacker.name == "Hindustan Unilever Limited")`
- **How it decides what to emit:** the Translator isn't one block of logic that handles
  every database. It's a thin dispatcher in front of one sub-translator per database, one
  for each database it currently supports. The dispatcher looks at which database the
  request needs to run on and hands the tree to that database's sub-translator, which
  emits that database's native query form. Supporting a new database later means plugging
  in one new sub-translator and one new capability table, not rewriting the existing ones.
- **What it's built with, and why:** a search for an existing library that already does
  "read RFC 9535, output a query for another engine" turned up nothing. Every RFC 9535
  library found only evaluates the filter in memory against JSON already loaded; it
  doesn't emit another query language. So the sub-translator is hand-built, walking the
  parsed filter tree using the **visitor pattern**, a standard way of walking a
  tree-shaped structure and doing something at each piece.
- **A few examples of how the syntax differs:**

  | Feature | RFC 9535 | Today's target database |
  | :--- | :--- | :--- |
  | Filter selector | `resources[?(@.resourceAttributes.packagedGoodsDeclaration.manufacturerOrPacker.name == 'Hindustan Unilever Limited')]` | `resources[*] ? (@.resourceAttributes.packagedGoodsDeclaration.manufacturerOrPacker.name == "Hindustan Unilever Limited")` |
  | String quotes | single or double, e.g. `'Hindustan Unilever Limited'` | double only, e.g. `"Hindustan Unilever Limited"` |
  | Existence check | `[?(@.resourceAttributes.rating)]` | `exists(@.resourceAttributes.rating)` |
  | Regex | `match(@.resourceAttributes.name, 'Unilever.*')` | `@.resourceAttributes.name like_regex "Unilever.*"` |
  | Array index | `[0]`, `[-1]` | `[0]`, `[last]` |

- **How the capability table and the sub-translator stay in step:** they describe the
  same boundary from two sides. The table says "this feature is supported here"; the
  sub-translator has to actually produce correct output for that feature. Their
  agreement isn't taken on faith: the official JSONPath Compliance Test Suite is run
  offline against every feature the table approves (see §4), so a mismatch between the
  two shows up as a failing test before release, not as a broken query in production.
- **What it can't translate:** nothing it's actually given. Filters that use unsupported
  features (recursive descent, `count()`, negative slice steps; full list in §4) were
  already rejected by the Validator's capability table, before the ACK. The Translator
  still refuses rather than guesses if it's ever handed something unexpected, but in
  normal operation that path never runs.

### Executor

- **What it takes:** the translated query from the Translator.
- **What it does:** sends the query to the database and runs it, returning the matching
  resources.
- **How it connects:** for today's database, the translated query string is passed as a
  **bind parameter**, never pasted directly into the query text. The database always
  treats it as a value to match against, never as something to execute. This is what
  keeps the filter safe to run even though it's fully client-supplied.
- **Why checking and running can't drift apart:** the same grammar produces the tree and
  the same capability table defines what's allowed, at validation time and at execution
  time. The same filter always produces the same tree, the same verdict, and the same
  translated query. There's no second opinion at run time to disagree with the first.

---

## 4. Challenges with the Proposed Design

### Some RFC 9535 features just don't map to the current database

This is the reject side of the Validator's capability table for the current database:

| RFC 9535 feature | Example | Why the current database can't do it |
| :--- | :--- | :--- |
| Recursive descent (`..`) | `$..name` | The closest thing the current database has visits things in a different order and can return the same match twice. A filter expecting RFC's clean, ordered list of matches would quietly get a messier one instead. |
| Wildcard on unknown type (`.*`) | `$.resources.*` | RFC's wildcard works the same way on an object or an array. The current database needs to know upfront which one it's dealing with and uses a different symbol for each. If that isn't known ahead of time, there's nothing to translate the wildcard to. |
| Filtering the root itself (`$[?…]`, `$[0]`) | `$[?@.active]` | The current database treats the very top of the document differently depending on whether it's an array or an object; RFC doesn't draw that line. Discover also requires every filter to start with a field name, so a root-level filter is turned away before it even gets this far. |
| Comparing two paths (`@.a == @.b`) | `$.resources[?(@.price == @.discountedPrice)]` | RFC has its own rules for comparing two paths to each other, like treating two missing values as equal. The current database can only compare a path against a fixed value, and handles missing or mismatched values differently, so it can't reproduce that result. |
| Existence check on multiple matches (`@.tags[*]`) | `$.resources[?(@.tags[*])]` | RFC's existence check just asks "did this match anything at all," even several things. The current database's version only works cleanly for a single, plain value. Once more than one match is possible, it runs into the same problems as recursive descent and wildcards above. |
| `count()` | `$.resources[?(count(@.tags[*]) == 3)]` | RFC's `count()` counts how many things a query matched. The closest thing the current database has counts the length of an array instead, which is a different number unless that value already happens to be a plain array. There's no real match for it. |
| Slice with a step, or negative step (`[::2]`, `[::-1]`) | `$.resources[0:10:2]` | The current database's slice only understands "from here to there." It has no way to skip every other item or go backwards, so a stepped or reversed slice has nothing to map to. |
| A few regex features (e.g. Unicode classes) | `match(@.resourceAttributes.name, '\p{L}+')` | RFC's regex allows Unicode character classes like `\p{L}` (any letter). The current database's regex engine speaks a different dialect that doesn't recognize that syntax, so the filter is turned away up front instead of failing later when the query actually runs. |

These are mostly edge cases. Normal Beckn filters (equality, ranges, AND/OR, checking
fields across catalogs, resources, and offers) all work fine.

### If it can't be translated, it's rejected, never guessed

When a filter uses a feature the capability table says the current database can't run,
the service sends a clear NACK at validation time instead of trying a partial or "close
enough" translation. A client either gets a correct result, or a clear "not supported."
Never a silently wrong answer.

An alternative was considered: filtering results in application code instead of in the
database, for the cases the database can't handle. This was ruled out because the filter
usually narrows the result set the most. Skipping it in the database query would mean
pulling a huge, unfiltered result set into memory first, which isn't practical at scale.

### No live database check before the ACK

An earlier version of this design had the Validator send a translated dry-run query to
the live database as a final safety net before ACKing. That check was removed
deliberately, and the trade-off is worth stating plainly:

- **What's given up:** if the capability table or a sub-translator ever has a bug, a
  filter could be ACKed and then fail during async processing, where it's logged as a
  defect instead of being NACKed to the client.
- **Why that's acceptable:** that bug class is caught offline instead, by the compliance
  suite below, which exercises every feature the capability table approves, end to end.
  A per-request dry run in production is a weaker version of the same guarantee at a much
  higher price.
- **What's gained:** the dry run was a database round-trip on the request thread for
  every previously unseen filter string, and clients control how many unique strings
  arrive. Removing it takes the database entirely off the request path: validation stays
  fast under load, can't exhaust database connections, and keeps working when the
  database has a brief outage.

### Tested against the official spec, not just claimed

The translated queries are run against the official JSONPath Compliance Test Suite and
compared to the expected output:

- Everything currently accepted and run gives the exact right answer. No wrong results.
- **Known gap:** a small number of inputs that the spec says are invalid are currently
  accepted instead of rejected (the parser is a bit too lenient in some edge cases). This
  doesn't cause wrong answers on valid queries, but it's a gap that should be closed.

---

## 5. Benefits of the Proposed Design

- **No lock-in to any one database.** The client-facing side doesn't know which database
  is behind it, queued requests carry the client's original filter rather than any
  translated form, and all database-specific logic sits behind one swappable
  sub-translator plus its capability table. Supporting another database later means
  writing one new translation path, not a rewrite.
- **Bad filters fail fast, and cheaply.** Clients get a clear NACK immediately, decided
  by a parse and a table lookup, instead of the request silently failing later in async
  processing.
- **The database is never touched before a request is accepted.** Validation cost per
  request is fixed and small, no matter how many new filter strings arrive, and
  validation keeps working through brief database outages.
- **RFC 9535 compliance can be proven, not just claimed.** Because it uses a real grammar
  instead of ad hoc string matching, it can be run against the official compliance test
  suite and the results shown.
