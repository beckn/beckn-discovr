# Crawler UI — Design Doc (simplified, demo-scoped)

**Goal:** from the UI, register a provider's DeDi endpoint, and watch the crawler pull
**manifest → index → catalogs → push → discoverable**, then see an ongoing dashboard of crawled
sources — with the **smallest possible change to the crawler**.

This is the demo-scoped design. It deliberately avoids a crawler HTTP API, dynamic-registry
services, and status endpoints. It is grounded in the crawler code as it exists today (`crawler/`).

Related: [`decentralized-catalog-crawler-poc.md`](./decentralized-catalog-crawler-poc.md) · UI lives in `reference-discover-ui/`.

---

## 1. Principle

The crawler **already** does the work and **already** persists the results (two Postgres tables) on
its normal schedule. So the UI is almost entirely a **reader** of existing state. The only thing the
UI can't do today is *add a source*, because the crawler reads its provider list from static config.

So we make exactly **one** crawler change — the source list becomes **table-first, config-fallback** —
and everything else is UI reading/writing Postgres. No crawler API.

---

## 2. The one crawler change

Today both crawl loops iterate `CrawlerProperties.providers` (static config). Change them to read a
small table, falling back to config when the table is empty.

**New table** — `V2__crawler_source.sql`:

```sql
CREATE TABLE crawler_source (
  id           UUID PRIMARY KEY,
  dedi_url     TEXT NOT NULL UNIQUE,      -- provider domain / manifest URL
  display_name TEXT,
  enabled      BOOLEAN NOT NULL DEFAULT true,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**Source resolution** — add a tiny `SourceRegistry` used by `Crawler.refreshManifests()` and
`Crawler.runIndexPass()` in place of `props.providers()`:

```
list() =
  SELECT dedi_url FROM crawler_source WHERE enabled = true
  → if that is empty, fall back to CrawlerProperties.providers (config)
```

Why this fits the existing code with no other change:
- `Crawler.pollProvider` already **lazily resolves** a provider whose manifest isn't cached
  (`if (registries == null) manifestResolver.resolve(provider)`). So a newly-inserted `crawler_source`
  row is picked up on the **next scheduled index poll** — no restart, no trigger endpoint.
- State still lands in the existing `index_crawl_state` / `catalog_part_state` tables exactly as today.

**That is the entire crawler change.** No controller, no `spring-boot-starter-web`, no status/submit
APIs, no exposed port.

---

## 3. Architecture

```
Browser (Crawler view)
   │  POST /api/crawler/sources { dediUrl, displayName }     ← "submit"
   │  GET  /api/crawler/status                                ← dashboard + pipeline
   ▼
Vite dev-proxy  (reference-discover-ui/vite.config.ts, Node — server-side)
   │   INSERT into crawler_source            (submit)
   │   SELECT from crawler_source + index_crawl_state + catalog_part_state   (status)
   ▼
PostgreSQL (catalog_db)                       ← same DB the crawler + discover use
   ▲
   │  reads crawler_source (∪ config), writes state tables on its normal schedule
Crawler service  (unchanged except §2)
```

- The **browser never touches Postgres.** All SQL is in the Node dev-proxy (server-side), same file
  and pattern as the existing discover proxy.
- **Accepted trade-off:** the UI proxy holds DB credentials and knows the crawler's table schema
  (one `INSERT`, a few `SELECT`s). This is the cost of having no crawler API; acceptable for a local
  demo, and it stays server-side. Not for production as-is.

---

## 4. UI proxy endpoints (browser ↔ proxy)

These live in `vite.config.ts` alongside the discover proxy. The proxy talks to Postgres with the
`pg` npm package (one new dev dependency), read-only except the single submit `INSERT`.

### 4.1 Submit a source
`POST /api/crawler/sources`

Request:
```json
{ "dediUrl": "techmart.example", "displayName": "TechMart" }
```
Behaviour: validate non-empty URL → `INSERT INTO crawler_source (id, dedi_url, display_name) …`
(generate a UUID). The crawler picks it up on its next poll.

Response `201`:
```json
{ "id": "…", "dediUrl": "techmart.example", "displayName": "TechMart", "status": "PENDING" }
```
Errors: `400` invalid URL · `409` `{ "error": "Source already registered" }` (UNIQUE `dedi_url`).

### 4.2 Status (dashboard + pipeline)
`GET /api/crawler/status`

Returns every source with its live crawl state, assembled from `crawler_source` +
`index_crawl_state` + `catalog_part_state`:

```json
{
  "sources": [
    {
      "id": "…",
      "dediUrl": "techmart.example",
      "displayName": "TechMart",
      "enabled": true,
      "stage": "DISCOVERABLE",                       // see §6
      "indexDigest": "sha-256:60e0…34ae",
      "lastCrawledAt": "2026-07-22T07:14:17Z",        // max(last_seen_at)
      "nextUpdate": "2026-08-04T00:00:00Z",
      "catalogs": [
        { "catalogId": "CAT-GROCERY-FRESHMART-100", "version": 1, "parts": 1,
          "digest": "sha-256:d3c4…", "lastSeenAt": "2026-07-22T07:14:17Z" }
      ],
      "counts": { "catalogs": 2, "resources": 4 }
    }
  ]
}
```

Notes on assembly:
- `crawler_source` gives the row + display name. A source with no state rows yet = `PENDING`.
- Link state rows to a source by matching the source's `dedi_url` against `index_url` / part URLs
  (URL prefix), since the state tables have no `source_id` (no schema change to them). This is the
  one slightly loose join; acceptable for the demo. (If it gets messy, add `source_id` later.)
- `resources` count: `catalog_part_state` stores parts, not resources. For the demo, show
  **catalog + part counts** from the tables, and optionally the **resource count from the Discover
  API** (already available) for the "N discoverable" number.

### 4.3 (optional) Disable a source
`DELETE /api/crawler/sources/{id}` → `UPDATE crawler_source SET enabled = false`. Crawler stops
crawling it next pass. Indexed catalogs remain in Discover (no un-publish path exists — call this out).

---

## 5. UI views (`reference-discover-ui`)

### 5.1 Navigation
Header gets a two-view switch: **Discover** (existing search) and **Crawler**.

### 5.2 Register a source
- One input: **DeDi endpoint** (domain or manifest URL) + optional **display name**.
- Hint under it: the resolved path the crawler fetches (`…/.well-known/dedi.json`).
- **Submit** → `POST /api/crawler/sources`. On success, show the new source in `PENDING` and start
  polling status; it flips to live once the crawler's next pass runs.

### 5.3 Trust-chain pipeline (the highlight)
For each source, render the chain the crawler walks — this is where the digest integrity shows:

```
①  Manifest         ②  Index            ③  Catalog parts     ④  Pushed          ⑤  Discoverable
   dedi.json           beckn-catalogs       CAT-… (1 part)       → /catalog/push     indexed
   digest ✓            digest ✓             digest ✓             ✓                   4 resources
   ●━━━━━━━━━━━━━━━━━━━━●━━━━━━━━━━━━━━━━━━━━●━━━━━━━━━━━━━━━━━━━━●━━━━━━━━━━━━━━━━━━━━○
```

Stage + counts + ✓ badges come from `GET /api/crawler/status`. Because state rows are written **only
after a verified digest and a 200 push**, a row's existence *is* the ✓ for that stage.

### 5.4 Sources dashboard
| Source | Last crawl | Next refresh | Catalogs | Resources | Status |
|--------|-----------|--------------|----------|-----------|--------|
| techmart.example | 40s ago | ~in 80s | 2 | 4 | ✓ Healthy |

Auto-refreshes (poll every ~2–5s) so you watch it update live. Row action: **Remove** (disable).
"→ View in Discover" jumps to the search view.

---

## 6. Stage derivation (from existing state)

`stage` per source, computed server-side in the proxy:

| Stage | Condition |
|-------|-----------|
| `PENDING` | `crawler_source` row exists, no matching `index_crawl_state` row yet |
| `INDEX_VERIFIED` | an `index_crawl_state` row matches the source (digest accepted) but no parts yet |
| `DISCOVERABLE` | `catalog_part_state` rows exist for the source (pushed + acked) |
| `FAILED` | (optional) surfaced from the crawler feedback log if wired; otherwise omit for the demo |

Manifest/index/parts sub-badges come from the presence of the index row + part rows + their digests.

---

## 7. Timing

A newly submitted source becomes visible in the pipeline within **one index-poll cycle**
(`crawler.indexPollInterval`, ~2 min in the compose file). For a snappier demo, lower that interval
in `docker-compose.crawler-poc.yml` (e.g. `30s`). The UI polls `GET /api/crawler/status` and advances
the stepper as rows appear.

---

## 8. Build order

1. **Crawler (only change):** `V2__crawler_source.sql`; `SourceRegistry` (table ∪ config); swap the
   two `props.providers()` loops to use it. Seed nothing — config still works when the table is empty.
2. **UI proxy:** add `pg`; `POST /api/crawler/sources` (INSERT) and `GET /api/crawler/status`
   (SELECT + assemble) in `vite.config.ts`; optional `DELETE`.
3. **UI views:** header nav (Discover | Crawler); Register form; pipeline stepper; sources dashboard;
   auto-refresh.
4. **Compose:** (optional) lower `indexPollInterval` for the demo; ensure the proxy can reach Postgres
   (`localhost:5434`).

Demo-first option: build the **Crawler view against a mocked `/api/crawler/status`** to lock the UX,
then wire the real SQL.

---

## 9. Open questions

- **OQ-1 — Submit input.** Domain vs full manifest URL? Accept either; if it doesn't end in the
  well-known path the crawler already appends `wellKnownPath`, so store what the user typed.
- **OQ-2 — Source ↔ state join.** URL-prefix match (no schema change) vs adding `source_id` to the
  state tables (cleaner joins, touches `StateStore` upserts). Demo: start with URL-prefix.
- **OQ-3 — Resource count.** From `catalog_part_state` we have parts, not resources; take the
  resource number from the Discover API, or show part counts only.
- **OQ-4 — Proxy DB access.** The proxy holds DB creds/schema knowledge. Fine for local demo; note it
  as the deliberate trade for "no crawler API."
- **OQ-5 — Remove semantics.** Disable-only (recommended); indexed catalogs stay in Discover.

---

## 10. Acceptance criteria

1. Submitting a DeDi endpoint inserts a `crawler_source` row; **no crawler restart** needed.
2. Within one poll cycle the pipeline advances manifest → index → catalogs → discoverable, with
   digest ✓ badges and correct catalog/part counts, from real state.
3. The sources dashboard lists each source with last-crawl / next-refresh / counts / status and
   auto-refreshes.
4. Newly crawled catalogs appear in the Discover view (and its catalog filter) with no further change.
5. With an **empty** `crawler_source` table, the crawler still crawls the **config** providers
   (no regression).
6. The crawler gains **only** the table + source-resolution change — no HTTP API, no new port.
```
