# Design: RFC 9535 JSONPath Filtering with Pluggable Database Translation

**Status:** Under Review
**Service:** `catalog-discover-job` (Beckn Discovr)
**Date:** 2026-07-02

---

## 1. Problem Statement

**The problem:** Today's database has its own path language (SQL:2016), which looks like
RFC 9535 but isn't. RFC 9535 is the actual JSONPath standard, and clients are forced to
write the database's dialect instead of RFC 9535.

### How filtering works today

Clients send a JSONPath-looking string in `message.intent.filters.expression`. Discovr
does not interpret this string itself; it passes it directly to the database:

- **Validation:** ask the database if it can parse the string. Yes, ACK. No, NACK.
- **Execution:** run that same string against the database, unchanged.

Whatever the database accepts becomes the effective filter language for clients. No
other part of the system understands the filter.

### Two different standards that look alike

The two look similar at a glance: the same `$`, the same `[...]`, the same `?(...)`
filter shape. The details differ underneath, in quoting, wildcards, and existence checks
(see §3 for exact examples). Because Discovr validates only against the database's
grammar, only that grammar's version of a filter is ever accepted. A filter that is
perfectly valid RFC 9535 is rejected today simply for not matching the database's rules,
even though a client has good reason to assume "JSONPath" means the standard itself.

This leads to two problems:

1. **Clients write the database's dialect, not standard JSONPath**, and cannot tell the
   two apart from the syntax alone.
2. **A future database change would force every client to migrate.** Every filter a
   client has already written is a string in today's one dialect. Replacing the database
   would require every client to rewrite every filter, on a timeline set by Discovr
   rather than by the client.

---

## 2. Goals

1. **Let clients use real RFC 9535 JSONPath**, not any one database's dialect.
2. **Keep backward compatibility.** The request declares its dialect in
   `message.intent.filters.type`: `rfc9535` for the standard path in this design, the
   existing legacy value (or no `type` at all) for filters written the old way. Legacy
   filters skip everything below and run exactly as they do today, so existing clients
   change nothing. RFC 9535 is opt-in; nobody is forced to migrate.

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

**What it takes**
The raw filter string the client sent.

**What it checks, in order, before the request is even acknowledged**

1. **Is it valid RFC 9535?** A syntax check against the RFC 9535 grammar, which also
   produces the parsed filter tree used by everything downstream.
2. **Can the current database actually run it?** Each node of the tree is looked up in a
   **capability table**: a plain list, kept per database, of which RFC 9535 features
   that database supports. The unsupported-features list in §4 is exactly the reject
   side of this table for the current database.

**What it deliberately does not do**
Talk to the database. Deciding ACK or NACK is a parse plus a table lookup, nothing more.
So a flood of never-seen-before filter strings can't tie up database connections, and
validation keeps working even while the database is briefly unavailable.

**Why the Validator decides everything up front**
Discover requests are ACKed immediately and processed later, asynchronously. Once that
async processing starts, there's no way back to send a NACK. So everything that could
reject a request has to be decided before the ACK, and a table lookup makes that
decision instant.

**What it produces**
If both checks pass, the request is acknowledged (ACK) and queued, carrying the client's
original filter string untouched. If either check fails, the request is rejected (NACK)
with a reason saying which check failed.

**What it's built with, and why**
The syntax check uses **ANTLR4**, a widely used parser generator: given the formal
RFC 9535 grammar, it generates the parser automatically. Hand-writing one this size is
slow and easy to get subtly wrong.

### Translator

**When it runs**
Asynchronously, after the ACK, when the queued request is picked up for processing. By
then the filter has already passed both validation checks, so the Translator never sees
a filter it can't handle.

**What it takes**
The parsed filter tree, and which database the query needs to run on. The filter is
parsed again from the queued request using the same grammar; parsing is cheap and always
produces the same tree, so the Translator works on exactly what the Validator approved.

**What it produces**
That database's own **native query form**: a path-language query string for today's
database, a structured query object for, say, a search engine. The contract is
"whatever that database natively runs", never "a string", because not every database has
a string path language to target.

**Example:** a real Discovr filter, matching resources from a specific manufacturer.

| | Filter |
| :--- | :--- |
| Input (RFC 9535) | `$.catalogs[*].resources[?(@.resourceAttributes.packagedGoodsDeclaration.manufacturerOrPacker.name == 'Hindustan Unilever Limited')]` |
| Output (today's target database) | `$.catalogs[*].resources[*] ? (@.resourceAttributes.packagedGoodsDeclaration.manufacturerOrPacker.name == "Hindustan Unilever Limited")` |

**How it decides what to emit**
The Translator itself doesn't know how to speak any database's query language. It's a
router in front of one sub-translator per database: it looks at which database this
request is configured to run against, then hands the tree to that database's
sub-translator, which is the piece that actually knows how to emit that database's
native query form. Supporting a new database later means plugging in one new
sub-translator and one new capability table for it, without touching the router or any
existing sub-translator.

**Today, one sub-translator**
Right now only one database exists, so there's only one sub-translator registered, and
the router has nothing to choose between. Adding Elasticsearch later doesn't change the
router: it means registering a second sub-translator (ES tree → ES query object, since
ES doesn't have a path-string language like today's database) and a second capability
table for what ES itself can and can't express, which may differ from §4's list.

**What it's built with, and why**
No existing library does "read RFC 9535, output a query for another engine"; every
library found only evaluates filters in memory. So the sub-translator is hand-built,
walking the parsed filter tree and emitting output piece by piece.

**A few examples of how the syntax differs**

| Feature | RFC 9535 | Today's target database |
| :--- | :--- | :--- |
| Filter selector | `resources[?(@.resourceAttributes.packagedGoodsDeclaration.manufacturerOrPacker.name == 'Hindustan Unilever Limited')]` | `resources[*] ? (@.resourceAttributes.packagedGoodsDeclaration.manufacturerOrPacker.name == "Hindustan Unilever Limited")` |
| String quotes | single or double, e.g. `'Hindustan Unilever Limited'` | double only, e.g. `"Hindustan Unilever Limited"` |
| Existence check | `[?(@.resourceAttributes.rating)]` | `exists(@.resourceAttributes.rating)` |
| Regex | `match(@.resourceAttributes.name, 'Unilever.*')` | `@.resourceAttributes.name like_regex "Unilever.*"` |
| Array index | `[0]`, `[-1]` | `[0]`, `[last]` |

**How the walk actually works**
The sub-translator walks the parsed filter tree (§3's tree diagram) one node at a time,
and for each node type it has one fixed rule for what that node becomes in the target
database's syntax. No interpretation, just a lookup per node, applied recursively until
the whole tree has been visited. For the manufacturer filter:

| Tree node | RFC 9535 piece | Rule applied | Output piece |
| :--- | :--- | :--- | :--- |
| Path step | `catalogs[*]`, `resources[*]` | Array steps are written the same way in both | `$.catalogs[*].resources[*]` |
| Comparison | the `EQUALS` condition | RFC's `[?( ... )]` becomes SQL:2016's `? ( ... )` | `? (...)` |
| Path reference | `@.resourceAttributes...name` | Copies through unchanged | `@.resourceAttributes...name` |
| String literal | `'Hindustan Unilever Limited'` | Single quotes become double quotes | `"Hindustan Unilever Limited"` |

Stitched together in tree order, these four pieces are exactly the output shown above.
If a node's type has no rule at all (the §4 list), the walk stops right there and
rejects the filter instead of guessing, which is also how the Validator can decide
ACK or NACK by doing this same walk once, with no database involved.

**What it can't translate**
Nothing it's actually given; filters with unsupported features (full list in §4) were
already rejected before the ACK. If it's ever handed something unexpected anyway, it
refuses rather than guesses.

### Executor

**What it takes**
The translated query from the Translator.

**What it does**
Sends the query to the database and runs it, returning the matching resources.

**How it connects**
The translated query is passed as a **bind parameter**, never pasted into the query
text, so the database treats it as a value, never as something to execute. That keeps a
fully client-supplied filter safe to run.

**Why checking and running can't drift apart**
The same grammar and the same capability table drive both. The same filter always
produces the same tree, the same verdict, and the same translated query.

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

An earlier version had the Validator send a dry-run query to the live database before
ACKing. It was removed deliberately: the dry run cost a database round-trip on the
request thread for every unseen filter string (and clients control how many arrive),
while the only thing it protected against, a bug in the capability table or a
sub-translator, is caught offline by the compliance suite below. The trade: such a bug
would now surface as a logged defect in async processing instead of a NACK.

### A filter can be hostile, not just invalid

The filter string is client-controlled input that reaches a parser and, later, a
database, so a filter can be deliberately crafted to hurt the system while staying just
inside the rules. Guardrails, cheapest first, each turn an attack into a clean NACK or a
harmless timeout:

| Guardrail | What it stops |
| :--- | :--- |
| Length cap on the raw expression, before parsing | Bounds everything below it: a filter can't be deeply nested or carry a huge regex without also being long |
| Nesting depth limit in the parser | Thousands of nested parentheses (`$[?((((...))))]`) crashing the request instead of getting a clean NACK |
| Regex pattern size cap at translation time | A crafted `match()` pattern that makes the database's regex engine work effectively forever |
| Hard time limit on every query, enforced by the database | The backstop for anything the other checks can't see: the worst a hostile filter achieves is one timed-out query, never a stuck connection |
| Rate limiting at the network edge | Floods of unique filter strings. Validation is already cheap (a parse and a table lookup); gateway limits cap even that |

Each guardrail ships with a test that sends the hostile input and asserts the clean NACK,
so "handled" is demonstrated, not assumed.

### Tested against the official spec, not just claimed

The translated queries are run against the official JSONPath Compliance Test Suite and
compared to the expected output:

- Everything currently accepted and run gives the exact right answer. No wrong results.
- **Known gap:** a small number of inputs that the spec says are invalid are currently
  accepted instead of rejected (the parser is a bit too lenient in some edge cases). This
  doesn't cause wrong answers on valid queries, but it's a gap that should be closed.

---

## 5. Benefits of the Proposed Design

- **No lock-in to any one database.** Queued requests carry the client's original filter,
  and all database-specific logic sits behind one swappable sub-translator plus its
  capability table. Another database later is one new translation path, not a rewrite.
- **Bad filters fail fast, and cheaply.** Clients get a clear NACK immediately, instead
  of the request silently failing later in async processing.
- **The database is never touched before a request is accepted.** Validation cost is
  fixed and small, and keeps working through brief database outages.
- **RFC 9535 compliance can be proven, not just claimed.** Because it uses a real grammar
  instead of ad hoc string matching, it can be run against the official compliance test
  suite and the results shown.
