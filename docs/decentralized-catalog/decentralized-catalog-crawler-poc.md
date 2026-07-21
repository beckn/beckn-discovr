# Decentralized Catalog Crawler — POC Design (DeDi format)

**Status:** Draft for review
**Date:** 2026-07-21
**Owner:** Manjunath Davanam
**Related:** "Decentralized Catalog" design doc (Ravi Mula / Pramod Varma)
**Audience:** whoever implements the POC — this doc should be enough to build from without guessing.

---

## 1. Background

Today a provider (BPP) **pushes** catalog data into Discovr via `/catalog/push`
(→ publish pipeline → PostgreSQL + Elasticsearch → discoverable).

The Decentralized Catalog model inverts this: a provider **hosts static JSON files** on their
own host and does nothing else. A **crawler**, running as part of the Discovery Service,
periodically **pulls** those files *in place*, verifies them, and feeds them into the *existing*
`/catalog/push` pipeline. Publishing becomes "save files + update the index"; there is no API
call from the provider.

The files follow the **DeDi** self-hosted publishing model — a chain, each link changing at a
different rate:

```
DeDi registry  →  manifest              →  index                         →  catalog files
 (who exists)     /.well-known/dedi.json    /dedi/beckn-catalogs.dedi.json    /catalogs/*.json
                  (rarely changes)          (changes every publish)           (the data)
```

This POC covers **manifest → index → catalog files**. DeDi registry enumeration is out of scope;
the crawler is configured directly with provider **domains** and derives the manifest path.

**We crawl files where the provider hosts them; we never copy them into our own bucket.** Only
the *indexed representation* lands in Discovr (via the pipeline). This is validated against a
live reference node (`angular-absently-gab.ngrok-free.dev`) whose three files match §4 exactly.

## 2. Goals & non-goals

### Goals
- Prove the pull model end-to-end: provider-hosted DeDi files → crawler → `/catalog/push` → discoverable.
- Detect change cheaply and precisely via the DeDi **digest chain** (host-independent).
- Guarantee integrity of every fetched file via its `sha-256` digest in the parent file.
- Be incremental across runs (persist crawl state).

### Non-goals (deferred; noted where they attach)
- **Signature (`proof`) verification** — the DeDi files carry an embedded `proof` (JWS, JCS
  canonicalization); the sample data is `UNSIGNED_LOCAL_TEST_DATA`. Integrity is still covered
  by the digest chain.
- **DeDi registry** enumeration of providers (POC configures domains directly).
- **SSRF / private-address refusal** and fetch-budget hardening.
- **Access control** for non-public catalogs — POC fetches only `visibility: "public"`.
- **Incremental *intra-catalog* diffs** — POC re-reads a whole catalog part when its digest changes.

## 3. Scope decisions (agreed)

| Decision | Choice | Why |
|----------|--------|-----|
| File format | **DeDi-native** (`dedi-manifest`, `dedi-file`, plain Beckn catalog) | Matches the live reference node and the registry's self-hosted model. |
| Entry point | Config = provider **domain**; crawler hardcodes the `/.well-known/dedi.json` path | `.well-known` is a fixed DeDi standard path; only the domain varies per provider. |
| Hosting | Provider hosts (domain **or** bucket); crawler reads **in place** | Preserves decentralization; copying to our bucket would recentralize. |
| Fidelity | Fetch + digest-chain verify + push; defer `proof` signatures & DeDi registry | Proves the novel parts; digests give integrity; auth is orthogonal. |
| Language | **Go**, own service (deferred, not blocking) | Lightweight concurrent poller, decoupled from the Java jobs. |
| Schema validation | Let the **publish pipeline** validate | Avoids reimplementing JSON-schema validation in the crawler for the POC. |
| State storage | A simple **PostgreSQL table** | Discovr already runs Postgres; safe across multiple crawler instances; supports staleness queries. |
| Concurrency | **Sequential** per pass | Simplest correct POC; parallelism is an easy later optimization. |

## 4. The three DeDi files

Runnable sample data lives under [`sample-bucket/`](./sample-bucket/) with a real, chained
`sha-256` digest set. Digest prefix is **`sha-256:`** (hyphen), per DeDi — not `sha256:`.

### 4.1 manifest — `/.well-known/dedi.json` (`type: dedi-manifest`)
The stable entry point, tethered to the provider's domain root. Fetched every pass but tiny;
it vouches for the index via `files[].digest`.

```json
{
  "dedi_version": "0.1",
  "type": "dedi-manifest",
  "domain": "techmart.example",
  "name": "TechMart Provider Node",
  "keys": [ { "kid": "key-001", "kty": "OKP", "crv": "Ed25519", "x": "11qYAY...HURo" } ],
  "updated_at": "2026-07-17T09:00:00Z",
  "next_update": "2026-07-24T09:00:00Z",
  "files": [
    { "registry": "beckn-catalogs",
      "url": "https://techmart.example/dedi/beckn-catalogs.dedi.json",
      "digest": "sha-256:60e0...34ae", "schema": "https://schema.nfh.global/.../schema.json",
      "state": "live" }
  ],
  "proof": { "verification_method": "key-001", "canonicalization": "JCS", "jws": "UNSIGNED_LOCAL_TEST_DATA..." }
}
```

| Field | POC use |
|-------|---------|
| `domain` | Provider/subscriber identity (`= bppId`); becomes `context.bppId` on push. Not the catalog's `provider.id`. |
| `keys[]` | Public signing key(s), inline. Read but **unused in POC** (proof deferred). |
| `files[]` | Points to the index: `url` (next hop) + `digest` (top-level change detector) + `state`. |
| `next_update` | Re-crawl hint (like a sitemap `changefreq`); crawler stays in control of its own cadence. |
| `proof` | Embedded signature over the manifest. **Deferred.** |

### 4.2 index — `/dedi/beckn-catalogs.dedi.json` (`type: dedi-file`)
A DeDi registry whose `records[]` are catalog pointers. Updated + re-hashed every publish.

```json
{
  "type": "dedi-file",
  "publisher": { "domain": "techmart.example", "key": { "kid": "key-001", "...": "..." } },
  "namespace": "techmart.example",
  "next_update": "2026-07-24T09:00:00Z",
  "registry": { "name": "beckn-catalogs", "state": "live", "updated_at": "..." },
  "records": [
    { "record_name": "CAT-ELECTRONICS-2026",
      "details": {
        "catalogId": "CAT-ELECTRONICS-2026", "version": 42,
        "status": "ACTIVE", "visibility": "public", "updatedAt": "...",
        "parts": [
          { "url": "https://techmart.example/catalogs/CAT-ELECTRONICS-2026-000.json",
            "digest": "sha-256:c0d6...2189", "lastModified": "..." }
        ] } }
  ],
  "proof": { "canonicalization": "JCS", "jws": "UNSIGNED_LOCAL_TEST_DATA..." }
}
```

| Field | POC use |
|-------|---------|
| `publisher.domain` / `namespace` | Must equal the manifest `domain`; else skip index (integrity). |
| `records[].details.catalogId` | Catalog identity. |
| `records[].details.version` | **Per-catalog** monotonic counter; a decrease = rollback/tampering → skip that record. |
| `records[].details.status` | `ACTIVE` / `RETIRED` (RETIRED carries no `parts`). |
| `records[].details.visibility` | `"public"` or `{ "networks": [...] }` (POC processes only `public`). |
| `records[].details.parts[].url` | Absolute URL the crawler GETs. |
| `records[].details.parts[].digest` | `sha-256:<hex>` of the part bytes — **integrity anchor + change detector**. |
| `next_update` | Re-crawl hint. |
| `proof` | Embedded signature over the index. **Deferred.** |

> The `proof` is **embedded** here (not a detached `.sig` file). Real verification: strip
> `proof`, JCS-canonicalize the rest, verify the JWS against `publisher.key`. Deferred for POC.

### 4.3 catalog part files — plain Beckn catalog JSON
Each part is one Beckn catalog document (`id`, `descriptor`, `provider`, `resources`, `offers`,
…) — the same shape that goes inside `message.catalogs[]` on `/catalog/push`. A large catalog
may split across parts sharing one catalog `id`; the pipeline merges them (§5.8 / OQ-3).

## 5. Crawler design

### 5.1 Components
| Component | Responsibility | Depends on |
|-----------|----------------|------------|
| `Config` | Load provider **domains**, push endpoint, intervals, timeouts, DB url | — |
| `HttpClient` | Conditional GET/HEAD with ETag; timeouts; returns `{status, body, etag}` | Config |
| `ManifestResolver` | Derive `<domain>/.well-known/dedi.json`; fetch; expose `domain`, index `url`+`digest` | HttpClient |
| `IndexPoller` | Fetch index (if changed); verify bytes vs manifest digest; parse + validate records | HttpClient |
| `Differ` | Compare index records/parts against `StateStore`; emit fetch/retire work items | StateStore |
| `Fetcher` | GET a part; verify `sha-256` against the index digest | HttpClient |
| `Pusher` | Build Beckn envelope; POST `/catalog/push`; interpret ACK/NACK | HttpClient |
| `StateStore` | Persist/read last-seen `{etag, version, digest}` in `crawl_state` | PostgreSQL |
| `FeedbackLog` | Append structured reject/skip records | — |
| `Crawler` | Orchestrate a pass across providers | all of the above |

### 5.2 Configuration (example)
```yaml
crawler:
  providers:                       # POC: domains only; DeDi registry replaces this later
    - "https://angular-absently-gab.ngrok-free.dev"
  wellKnownPath: "/.well-known/dedi.json"   # fixed DeDi standard; appended to each domain
  pushEndpoint: "http://localhost:8085/catalog/push"   # catalog-publish-job
  pollInterval: "24h"              # default cadence; next_update / a change signal may shorten it
  http:
    timeout: "30s"
    maxPartBytes: 10485760         # 10 MB safety cap per part fetch
  db:
    url: "${CRAWLER_DB_URL}"       # e.g. postgres://.../discovr
  feedbackLogPath: "./feedback.log"
```

### 5.3 State store (PostgreSQL table)
One row per fetched URL — the crawler's memory of "what I saw last time." A row is upserted
**only after** its push succeeds, so a crash mid-pass re-does that catalog rather than skipping it.

```sql
CREATE TABLE crawl_state (
  url          TEXT PRIMARY KEY,   -- manifest url, index url, or part url
  etag         TEXT,               -- optional HTTP ETag (if the host sends one)
  digest       TEXT,               -- index row: last index digest; part row: last verified part digest
  version      BIGINT,             -- part row: owning catalog's details.version (rollback guard)
  source_updated_at TIMESTAMPTZ,   -- from the file (updatedAt / lastModified)
  last_seen_at TIMESTAMPTZ         -- when the crawler last checked this url
);
```

Reads driving each check:
- index row `digest` → compare to manifest `files[].digest` (did the index change at all?).
- index row `etag` → optional `If-None-Match` on the index.
- part row `digest` → compare to the index's `parts[].digest` (did this catalog change?).
- part row `version` → compare to the record's `details.version` (rollback guard; all parts of a
  catalog share its version).

Writes use `INSERT ... ON CONFLICT (url) DO UPDATE`.

**Deferred columns (add with DeDi, not now).** Kept URL-keyed for the POC because every check is
by URL. Add when the DeDi layer lands:
- `subscriber_id` — the provider/subscriber `domain` (= `bppId`; **not** the catalog's
  `provider.id`). Needed for per-provider de-registration cleanup + grouping (OQ-4).
- `catalog_id` — the owning catalog of a part row. Needed for real RETIRED cleanup (OQ-1): a
  RETIRED record carries no `parts`, so without it the crawler can't map a retired catalog back
  to its part-state rows.

### 5.4 One crawl pass
```
FOR each configured provider domain:

  1. RESOLVE
     GET <domain>/.well-known/dedi.json           (manifest — small, fetched every pass)
     → domain, index url, index digest (files[].digest)

  2. CHEAP TOP-LEVEL CHECK
     manifest.files[].digest == stored index digest ?
       yes → nothing changed anywhere → DONE for this provider
       no  → the index changed → continue
     (optional: also send If-None-Match on the index if the host gave an ETag)

  3. FETCH + VALIDATE INDEX
     GET index url
     sha-256(index bytes) == manifest.files[].digest ?  no → feedback + skip provider
     publisher.domain == manifest.domain             ?  no → feedback + skip
     [DEFERRED] verify index proof (JWS/JCS) against publisher.key

  4. DIFF  (per record)
     status == RETIRED      → retire work item (no fetch; OQ-1)
     visibility != "public" → skip (POC)
     details.version < stored version → rollback/tampering → feedback + skip record
     FOR each part:
       part.digest == stored digest → skip (unchanged)
       else                          → fetch work item
     a record with >=1 changed part → ONE push (all its parts)

  5. FETCH + VERIFY  (changed parts)
     GET part.url (reject if body > maxPartBytes)
     sha-256(body) == strip("sha-256:", part.digest) ?  no → feedback + skip this catalog

  6. PUSH  (per changed catalog, atomically)
     POST /catalog/push  { context, message: { catalogs: [ <part(s)> ] } }
       200 Ack → success
       non-200 / NACK → feedback + skip (state NOT advanced → retried next pass)

  7. PERSIST STATE  (only for catalogs that pushed 200)
     upsert part digests/version; then upsert index digest (+ ETag); manifest untouched unless moved
```

### 5.5 How a catalog update is detected (walkthrough)
A catalog edit ripples **up** the digest chain, and the crawler detects it **top-down**.

**Provider side** (this *is* publishing — three bottom-up edits):
1. Save the new catalog file → its `sha-256` changes.
2. Index: write the new `parts[].digest`, bump that record's `version`, update `updatedAt` → the
   index bytes change → its `sha-256` changes.
3. Manifest: write the index's new digest into `files[].digest`.

**Crawler side** (edit to `CAT-ELECTRONICS-2026-001`, using sample values):
1. Manifest `files[].digest`: `60e0…` → `bbbb…` ≠ stored → index changed.
2. Fetch index; compare part digests: `…-000` `c0d6…` unchanged (skip); `…-001` `dc44…` → `aaaa…`
   changed → fetch. Version `43 ≥ 42` ✓.
3. Fetch **only that one catalog**, verify `sha-256` == `aaaa…`, push.
4. Persist new digests + version.

Result: the crawler downloaded the manifest (tiny) + index (small) + **exactly one catalog**.
The **contract**: a provider MUST update the index when a catalog changes — otherwise the
crawler won't notice, and a fetched-but-stale file would fail digest verification anyway.

### 5.6 Two change checks (digest authoritative, ETag optional)
| Level | Mechanism | Availability | Role |
|-------|-----------|--------------|------|
| Content | manifest→index digest, index→part digests | **always** (in the files) | authoritative: did it change + integrity. Host-independent. |
| HTTP | `ETag` / `If-None-Match` (or `Last-Modified`) | best-effort (bucket: reliable; arbitrary domain: maybe/none) | pure optimization: skip a fetch when the host supports it. |

The crawler must work with **zero** HTTP caching headers (as the live ngrok node demonstrated).
The digest chain is the mechanism; ETag only saves a fetch. A stale/missing ETag costs freshness,
never correctness.

### 5.7 Digest handling
- Digests are `"sha-256:<lowercase-hex>"`. Parse the algorithm before `:`; strip it before comparing.
- Compute `sha-256` over the **exact response bytes**, before any JSON re-serialization.
- Compare case-insensitively; a mismatch is a hard reject (never index unverified bytes).

### 5.8 Push envelope (concrete)
The crawler synthesizes a fresh `context` per push (new UUIDs; re-pushing is safe — the pipeline
upserts). `message.catalogs` is an **array** (verified against `ParseStep.extractCatalogs`), so
all parts of one catalog go in a single call and the pipeline merges them by catalog `id`.

```json
{
  "context": {
    "action": "catalog/publish",
    "bppId": "techmart.example",
    "bppUri": "https://techmart.example/beckn",
    "messageId": "<uuid>", "transactionId": "<uuid>",
    "timestamp": "2026-07-21T10:00:00Z", "version": "2.0.0"
  },
  "message": { "catalogs": [ { "id": "CAT-ELECTRONICS-2026", "resources": [] } ] }
}
```
> `bppId` = manifest `domain`. `bppUri` from the catalog file's `bppUri`. The controller only
> *requires* `context` + `messageId`/`transactionId`; other fields help the pipeline index (OQ-2).

### 5.9 Error handling & retries
| Situation | Behaviour |
|-----------|-----------|
| Manifest/index fetch timeout or 5xx | Log, retry with backoff (~3 tries); else end pass for that provider. State untouched. |
| Index bytes ≠ manifest digest / domain mismatch | FeedbackLog + skip the whole index; keep previous state. |
| Record version regressed | FeedbackLog + skip that record. |
| Part fetch fails / oversized | FeedbackLog + skip that **catalog** (no partial push). |
| Digest mismatch | FeedbackLog (`reason=digest_mismatch`) + skip that catalog. |
| Push non-200 / NACK | FeedbackLog + do **not** advance state → retried next pass. |

Guiding rule: **state advances only after a confirmed 200 Ack** — every failure self-heals next
pass, and a partially-fetched catalog is never indexed.

### 5.10 Feedback log (format)
One JSON object per line:
```json
{"ts":"...","domain":"techmart.example","catalogId":"CAT-ELECTRONICS-2026","stage":"verify","reason":"digest_mismatch","detail":"expected sha-256:c0d6.. got sha-256:9af3.."}
```
`stage` ∈ `{resolve, poll, validate, fetch, verify, push}`. `reason` is a short stable code.

### 5.11 Optional accelerator (later)
A provider "change signal" only *triggers a pass sooner*; it never carries data. Content is still
fetched + verified, so a spoofed or lost signal costs freshness, never correctness. Out of scope.

## 6. POC build order
1. **Point at a provider domain** (the live ngrok node, or self-host `sample-bucket/`). Confirm
   `<domain>/.well-known/dedi.json` returns the manifest.
2. **Resolve + top-level check** — manifest → compare index digest; fetch index only if changed.
3. **Diff + fetch + digest verify** — walk records, fetch changed parts, verify `sha-256`.
4. **Push** — wrap + `POST /catalog/push`; confirm the catalog appears via `/beckn/discover`.
5. **Incrementality** — edit a catalog, re-hash into index + manifest, bump the record version;
   re-run; confirm only that part is refetched and pushed, and RETIRED is handled.

## 7. Acceptance criteria (POC is "done" when)
- A fresh run against a provider domain pushes both public catalogs' parts; they are returnable
  via `/beckn/discover`.
- The network-restricted catalog (`CAT-EON-EXCLUSIVE-2026`) is **not** pushed.
- A second run with **no change** stops after the manifest top-level check → **zero** pushes.
- Editing one part (re-hashed up the chain + version bump) refetches/pushes **only that part**.
- A deliberately wrong digest causes that catalog to be **rejected** (feedback logged), not indexed.
- The `RETIRED` catalog is removed / never added (per OQ-1 outcome).

## 8. Open questions
- **OQ-1 — RETIRED handling.** The pipeline is publish/upsert-oriented; removal likely needs a
  delete path distinct from `/catalog/push`. *POC fallback:* log the retire intent and skip.
- **OQ-2 — Envelope context fields.** Controller requires `context` + `messageId`/`transactionId`;
  confirm the full downstream set (`bppId`, `bppUri`, `action`, `networkId`, `version`) for correct indexing.
- **OQ-3 — Multi-part merge.** `message.catalogs` is an array and parts share a catalog `id`;
  confirm the pipeline **merges** (default MERGE mode) rather than last-write-wins.
- **OQ-4 — Signatures & registry.** `proof` is embedded (JWS, JCS) with the key inline in the
  manifest; wire verification + DeDi registry enumeration in the non-POC version. Also reconcile
  `next_update` (re-crawl hint) vs a hard trust bound (our earlier `validUntil`).

## 9. Sample data
See [`sample-bucket/`](./sample-bucket/) — a ready-to-host DeDi node (`.well-known/dedi.json`,
`dedi/beckn-catalogs.dedi.json`, `catalogs/*.json`) with a real chained digest set, plus a README
covering hosting, digest recomputation, and an incrementality test. It intentionally includes one
multi-part public catalog, one network-restricted catalog, and one RETIRED record so all §5.4
branches are exercised.
