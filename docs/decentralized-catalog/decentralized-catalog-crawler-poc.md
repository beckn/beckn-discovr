# Decentralized Catalog Crawler — POC Design

**Status:** Draft for review
**Date:** 2026-07-21
**Owner:** Manjunath Davanam
**Related:** "Decentralized Catalog" design doc (Ravi Mula / Pramod Varma)
**Audience:** whoever implements the POC — this doc should be enough to build from without guessing.

---

## 1. Background

Today a provider (BPP) **pushes** catalog data into Discovr through an API
(`/catalog/push` → publish pipeline → PostgreSQL + Elasticsearch → discoverable).

The Decentralized Catalog model inverts this: a provider **hosts static JSON files**
on any public object store and does nothing else. A **crawler**, running as part of the
Discovery Service, periodically **pulls** those files, verifies them, and feeds them into
the *existing* publish pipeline. Publishing becomes "save files to a bucket"; there is no
API call from the provider.

The full model has a four-link chain:

```
DeDi registry  →  manifest  →  index  →  catalog files
 (who exists)     (where)      (what)     (the data)
```

This POC covers **manifest → index → catalog files**. DeDi enumeration is out of scope;
the crawler is configured directly with one or more manifest URLs.

## 2. Goals & non-goals

### Goals
- Prove the pull model end-to-end: static files on **GCS** → crawler → `/catalog/push` → discoverable.
- Detect change cheaply (HTTP `ETag`/`HEAD`) and fetch **only** what changed.
- Guarantee integrity of every fetched file via the `sha256` digest in the index.
- Be incremental across runs (persist crawl state).

### Non-goals (deferred; noted where they attach)
- **Index signature verification** and signing-key resolution (integrity is still covered by digests).
- **DeDi registry** enumeration of providers.
- **SSRF / private-address refusal** and fetch-budget hardening.
- **Access control** for non-public catalogs — POC fetches only `visibility: "public"`.
- **Incremental *intra-catalog* diffs** (changed-resource-only) — POC re-reads a whole
  catalog part when its digest changes.

## 3. Scope decisions (agreed)

| Decision | Choice | Why |
|----------|--------|-----|
| Storage | Public **GCS** bucket | Stack already on GCP/GKE; clean ETag/HEAD semantics; no extra creds. |
| Fidelity | Fetch + digest + push; defer signatures & DeDi | Proves the novel/risky parts; digests give integrity; auth is orthogonal. |
| Language | **Go**, own service (deferred, not blocking) | Lightweight concurrent poller, decoupled from the Java jobs. |
| Schema validation | Let the **publish pipeline** validate | Avoids reimplementing JSON-schema validation in the crawler for the POC. |
| State storage | A simple **PostgreSQL table** | Discovr already runs Postgres; a table is safe across multiple crawler instances (a flat file is not) and supports staleness queries later. |
| Concurrency | **Sequential** per pass (one provider, one part at a time) | Simplest correct POC; parallelism is an easy later optimization. |

## 4. The three hosted files

All examples exist as runnable sample data under
[`sample-bucket/beckn/`](./sample-bucket/beckn/). Digests in the sample `index.json` are
real `sha256` values of the sample part files.

### 4.1 manifest.json — stable entry point
Rarely changes (only on host move or key rotation). The crawler is pointed at this URL,
fetches it once, and caches it.

```json
{
  "subscriberId": "bpp.techmart.com",
  "indexUrl": "https://storage.googleapis.com/techmart-beckn/beckn/index.json",
  "signingKey": "bpp.techmart.com|key-001",
  "updatedAt": "2026-01-05T00:00:00Z"
}
```

| Field | Meaning | POC use |
|-------|---------|---------|
| `subscriberId` | Provider identity (BPP id) | Must equal `index.subscriberId`; becomes `context.bppId` on push. |
| `indexUrl` | Absolute URL of this provider's index | The next hop the crawler polls. |
| `signingKey` | Key id used to sign the index | Read but **unused in POC** (signatures deferred). |
| `updatedAt` | Last manifest change | Informational. |

### 4.2 index.json — the churn file (updated every publish)
Lists every catalog with status, visibility, and part-file URLs + digests. `version`
increases monotonically; `validUntil` bounds how long a cached copy may be believed.

| Field | Meaning | POC use |
|-------|---------|---------|
| `subscriberId` | Provider identity | Must match the manifest; else skip index (integrity). |
| `version` | Monotonic publish counter | A decrease vs last-seen = rollback/tampering → skip + feedback. |
| `updatedAt` | When the index was written | Informational. |
| `validUntil` | Expiry of trust for this index | If in the past → index is stale → skip + feedback. |
| `catalogs[].catalogId` | Catalog identity | Key for state + feedback. |
| `catalogs[].status` | `ACTIVE` / `RETIRED` | RETIRED carries no `parts` → retire flow (OQ-1). |
| `catalogs[].visibility` | `"public"` or `{ "networks": [...] }` | POC processes only `"public"`. |
| `catalogs[].updatedAt` | Catalog-level change time | Coarse "did this catalog change" hint. |
| `catalogs[].schemaTypes[]` | Declared schema context URLs | Informational for POC. |
| `catalogs[].parts[].url` | Absolute URL of a part file | What the crawler GETs. |
| `catalogs[].parts[].digest` | `sha256:<hex>` of the part bytes | **Integrity anchor** + change detector. |
| `catalogs[].parts[].lastModified` | Part change time | Human/debug hint; digest is authoritative. |

(Full example: [`sample-bucket/beckn/index.json`](./sample-bucket/beckn/index.json).)

### 4.3 catalog part files — plain Beckn catalog JSON
Each part file is exactly one Beckn catalog document (`id`, `descriptor`, `provider`,
`resources`, …) — the same shape that goes inside `message.catalogs[]` on `/catalog/push`
today. A large catalog may be split across several part files
(`electronics-2026-000`, `-001`, …); **every part shares the same catalog `id`**, and the
publish pipeline merges them (see §5.7 / OQ-3).

(Full example: [`sample-bucket/beckn/electronics-2026-000.json`](./sample-bucket/beckn/electronics-2026-000.json).)

## 5. Crawler design

### 5.1 Components
Keep these as separate, independently testable units:

| Component | Responsibility | Depends on |
|-----------|----------------|------------|
| `Config` | Load manifest URLs, push endpoint, intervals, timeouts, paths | — |
| `HttpClient` | Conditional GET/HEAD with ETag; timeouts; returns `{status, body, etag}` | Config |
| `ManifestResolver` | Fetch + cache manifest; expose `indexUrl`, `subscriberId` | HttpClient |
| `IndexPoller` | Conditional GET index; parse + validate (version/validUntil/subscriberId) | HttpClient |
| `Differ` | Compare index against `StateStore`; emit fetch/retire work items | StateStore |
| `Fetcher` | GET a part; verify `sha256` against index digest | HttpClient |
| `Pusher` | Build Beckn envelope; POST `/catalog/push`; interpret ACK/NACK | HttpClient |
| `StateStore` | Persist/read last-seen `{etag, version, digest}` in the `crawl_state` table | PostgreSQL |
| `FeedbackLog` | Append structured reject/skip records | — |
| `Crawler` | Orchestrate a pass across providers | all of the above |

### 5.2 Configuration (example)
```yaml
crawler:
  providers:                       # POC: one entry; DeDi replaces this later
    - manifestUrl: "https://storage.googleapis.com/techmart-beckn/beckn/manifest.json"
  pushEndpoint: "http://localhost:8085/catalog/push"   # catalog-publish-job
  pollInterval: "24h"              # default cadence; a change signal would trigger sooner
  http:
    timeout: "30s"
    maxPartBytes: 10485760         # 10 MB safety cap per part fetch
  db:                              # state store (see 5.3)
    url: "${CRAWLER_DB_URL}"       # e.g. postgres://.../discovr
  feedbackLogPath: "./feedback.log"
```

### 5.3 State store (PostgreSQL table)
One row per fetched URL — this is the crawler's memory of "what I saw last time." A row is
written/updated **only after** the corresponding push succeeds (index-ETag row only after all
its catalogs are handled), so a crash mid-pass re-does that catalog rather than skipping it.

```sql
CREATE TABLE crawl_state (
  url          TEXT PRIMARY KEY,   -- index URL or part URL
  etag         TEXT,               -- index rows: last ETag seen (for If-None-Match)
  version      BIGINT,             -- index rows: last index.version accepted
  digest       TEXT,               -- part rows: last verified sha256:<hex>
  source_updated_at TIMESTAMPTZ,   -- from the file (index/part updatedAt)
  last_seen_at TIMESTAMPTZ         -- when the crawler last checked this url
);
```

Reads driving each check:
- `SELECT etag FROM crawl_state WHERE url = <indexUrl>` → sent as `If-None-Match`.
- `SELECT version ...` → compared: `newIndex.version >= stored.version` (else rollback).
- `SELECT digest FROM crawl_state WHERE url = <partUrl>` → compared to the index digest to
  decide whether the part changed.

Writes use `INSERT ... ON CONFLICT (url) DO UPDATE` (upsert) after a successful push.
`last_seen_at` also supports the later staleness rule ("drop catalogs not re-verified within
the window") without extra bookkeeping.

### 5.4 One crawl pass
```
FOR each configured manifest URL:

  1. RESOLVE
     GET manifest.json (cached; refetch only if its own ETag changed)
     → indexUrl, subscriberId

  2. CHEAP POLL
     conditional GET index.json  (If-None-Match: <stored index ETag>)
       304 → nothing changed → DONE for this provider
       200 → new index body + new ETag

  3. VALIDATE INDEX  (any failure → feedback + skip whole index, keep old state)
     - index.subscriberId == manifest.subscriberId
     - index.version >= last-seen version        (decrease → rollback/tampering)
     - index.validUntil is in the future

  4. DIFF  (decide work)
     FOR each catalog:
       status == RETIRED     → retire work item (no fetch)
       visibility != "public"→ skip (POC)
       collect changed parts:
         part.digest == last-seen digest → skip (unchanged)
         else                            → fetch work item
       if catalog has >=1 changed part → it becomes ONE push (all its parts)

  5. FETCH + VERIFY  (per changed part)
     GET part.url (reject if body > maxPartBytes)
     normalize+compare: sha256(body) == strip("sha256:", part.digest)
       mismatch → feedback + skip this catalog (do NOT push a partial catalog)

  6. PUSH  (per changed catalog, atomically)
     POST /catalog/push with all the catalog's parts in message.catalogs[]
       200 Ack  → success
       non-200 / NACK → feedback + skip (state NOT advanced → retried next pass)

  7. PERSIST STATE  (only for catalogs that pushed 200)
     save new part digests/updatedAt; then save new index ETag + version
```

### 5.5 Two independent change checks
| Level | Mechanism | Cost | Role |
|-------|-----------|------|------|
| HTTP | `ETag` / `If-None-Match` (or `HEAD` / `If-Modified-Since`) on `index.json` | cheapest | skip re-reading the index when nothing was published |
| Content | `digest` per part in the trusted index | one GET per changed file | authoritative: which catalogs to refetch + integrity check |

Content is **always fetched and verified from source**. The ETag only saves work — a stale
or missing ETag costs freshness, never correctness. (If a host returns no `ETag`, the
crawler still works: it just always fetches the index and relies on per-part digests.)

### 5.6 Digest handling
- Index digests are `"sha256:<lowercase-hex>"`. Strip the `sha256:` prefix before comparing.
- Compute `sha256` over the **exact response bytes**, before any JSON re-serialization.
- Compare case-insensitively; a mismatch is a hard reject (never index unverified bytes).

### 5.7 Push envelope (concrete)
The crawler synthesizes a fresh `context` per push (`messageId`/`transactionId` are new
UUIDs each time; re-pushing is safe because the pipeline upserts). `message.catalogs` is an
**array** (verified against `ParseStep.extractCatalogs` → `message.path("catalogs")`), so all
parts of one catalog go in a single call and the pipeline merges them by catalog `id`.

```json
{
  "context": {
    "action": "catalog/publish",
    "bppId": "bpp.techmart.com",
    "bppUri": "https://techmart.com/beckn",
    "messageId": "b6c1e2f0-1111-4a2b-8c3d-000000000001",
    "transactionId": "b6c1e2f0-2222-4a2b-8c3d-000000000002",
    "timestamp": "2026-07-21T10:00:00Z",
    "version": "2.0.0"
  },
  "message": {
    "catalogs": [
      { "id": "bpp.techmart.com/electronics-2026", "descriptor": { "...": "..." }, "resources": [] }
    ]
  }
}
```
> `bppId` = `subscriberId`. `bppUri` may be taken from the catalog file's `bppUri`.
> The controller only *requires* `context` + `messageId`/`transactionId`; the other fields
> are supplied so the downstream pipeline indexes correctly (see OQ-2).

### 5.8 Error handling & retries
| Situation | Behaviour |
|-----------|-----------|
| Manifest/index fetch timeout or 5xx | Log, retry with backoff (e.g. 3 tries); if still failing, end pass for that provider — retry next cycle. State untouched. |
| Index validation fails (§5.4 step 3) | FeedbackLog + skip the whole index; keep previous state. |
| Part fetch fails / oversized | FeedbackLog + skip that **catalog** (no partial push); other catalogs continue. |
| Digest mismatch | FeedbackLog (reason=`digest_mismatch`) + skip that catalog. |
| Push non-200 / NACK | FeedbackLog + do **not** advance that catalog's state → retried next pass. |

Guiding rule: **state advances only after a confirmed 200 Ack**, so every failure is
self-healing on the next pass, and a partially-fetched catalog is never indexed.

### 5.9 Feedback log (format)
One JSON object per line (the "feedback log the provider can read" from the design):
```json
{"ts":"2026-07-21T10:00:00Z","subscriberId":"bpp.techmart.com","catalogId":"bpp.techmart.com/electronics-2026","stage":"verify","reason":"digest_mismatch","detail":"expected sha256:1824.. got sha256:9af3.."}
```
`stage` ∈ `{resolve, poll, validate, fetch, verify, push}`. `reason` is a short stable code.

### 5.10 Optional accelerator (later)
A provider "change signal" only *triggers a pass sooner*; it never carries data. The same
crawl runs and content is still fetched + verified, so a spoofed or lost signal costs
freshness, never correctness. Out of scope for the POC.

## 6. POC build order
1. **Provision** — create the public GCS bucket, upload `sample-bucket/beckn/`, confirm
   `curl -I` returns an `ETag` on `index.json`.
2. **Resolve + poll** — manifest → conditional GET on index; prove 200 vs 304.
3. **Diff + fetch + digest verify** — fetch changed parts, verify digests.
4. **Push** — wrap + `POST /catalog/push`; confirm the catalog appears via `/discover`.
5. **Incrementality** — bump `version`, change one part's bytes+digest, re-run; confirm
   only that part is refetched and pushed, and RETIRED is handled.

## 7. Acceptance criteria (POC is "done" when)
- A fresh run against the sample bucket pushes both public catalogs' parts and they are
  returnable via `/beckn/discover`.
- The network-restricted catalog (`eon-exclusive-2026`) is **not** pushed.
- A second run with **no bucket change** results in a `304` and **zero** pushes.
- Editing one part (bytes + digest + `version` bump) causes **only that part** to be
  refetched and pushed on the next run.
- A deliberately wrong digest in the index causes that catalog to be **rejected** (feedback
  logged) and **not** indexed.
- The `RETIRED` catalog is removed from / never added to the index (per OQ-1 outcome).

## 8. Open questions
- **OQ-1 — RETIRED handling.** The publish pipeline is publish/upsert-oriented. Removing a
  catalog likely needs a delete/retire path distinct from `/catalog/push`. Confirm the
  mechanism before building step 6's retire branch. *POC fallback if undecided:* log the
  retire intent to the feedback log and skip (no delete), so the POC never blocks on it.
- **OQ-2 — Envelope context fields.** The push controller requires `context` with
  `messageId`/`transactionId`; the crawler synthesizes these. Confirm the full set of
  context fields the *downstream* pipeline expects (`bppId`, `bppUri`, `action`, `networkId`,
  `version`) so pushed catalogs index correctly.
- **OQ-3 — Multi-part merge.** `message.catalogs` is an array and parts share a catalog `id`.
  Confirm the pipeline **merges** array elements/sequential pushes by `id` (default publish
  mode is MERGE) rather than last-write-wins — otherwise a multi-part catalog loses earlier
  parts. This POC assumes MERGE.
- **OQ-4 — Signatures & DeDi** (deferred) — how signing keys resolve and how providers are
  enumerated from the registry in the non-POC version.

## 9. Sample data
See [`sample-bucket/`](./sample-bucket/) — a ready-to-upload `beckn/` folder plus a README
covering GCS upload, digest recomputation, and an incrementality test. The sample index
intentionally includes one multi-part public catalog, one network-restricted catalog, and
one RETIRED catalog so all branches of §5.4 are exercised.
