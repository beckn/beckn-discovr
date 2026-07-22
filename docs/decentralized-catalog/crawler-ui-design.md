# Crawler UI — Design (as built)

**Goal:** from the UI, register a provider's `dedi.json`, then see per-provider **catalog count**
and **last-synced time**. The crawler picks up a newly registered source on its next poll and syncs
changed catalogs into Discover. Kept deliberately simple — no crawler HTTP API, no pipeline
visualization.

This doc reflects the **shipped** implementation. Crawler lives in `crawler/`; UI in
`reference-discover-ui/`. Related: [`decentralized-catalog-crawler-poc.md`](./decentralized-catalog-crawler-poc.md).

---

## 1. Shape

```
Browser (Crawler tab)
   │  POST /api/crawler/sources { dediUrl, displayName }   ← register
   │  GET  /api/crawler/sources                             ← providers table (poll 5s)
   │  DELETE /api/crawler/sources/{id}                      ← stop crawling
   ▼
Vite dev-proxy (reference-discover-ui/vite.config.ts, Node — server-side, uses `pg`)
   │   INSERT/UPDATE/SELECT on crawler_source; SELECT (join) on the two crawl-state tables
   ▼
PostgreSQL (catalog_db)
   ▲
   │  reads crawler_source (crawler.source=db), writes crawl state + stamps provider identity
Crawler service
```

- The **browser never touches Postgres** — all SQL is in the Node dev-proxy (server-side).
- **Deliberate trade-off:** the proxy holds DB credentials + schema knowledge (the price of having
  no crawler API). Fine for a local demo; not for production.

---

## 2. The crawler already provides the source switch (no UI-only hack needed)

Implemented in `crawler/` (by the crawler owner):
- **`crawler.source`** config switch — `config` (`crawler.providers` list) or **`db`**
  (`crawler_source` table). The POC compose defaults to **`db`**.
- **`DbSourceRegistry`** reads `SELECT dedi_url, display_name FROM crawler_source WHERE status = true`
  **on every index poll** (default `crawler.index-poll-interval = 1m`), so a row added in the UI is
  crawled within ~1 minute — no restart.
- `dedi_url` is a **full manifest URL** (e.g. `https://…/dedi.json`); the crawler fetches it directly.

So the UI just writes rows into `crawler_source`; the crawler does the rest.

---

## 3. Data model

### 3.1 `crawler_source` — the provider registry (UI writes here)
`V2__crawler_source.sql` + `V3__provider_identity.sql`:

| Column | Type | Meaning |
|---|---|---|
| `id` | uuid (PK, `gen_random_uuid()`) | source id |
| `dedi_url` | text (UNIQUE) | the manifest URL the user registered |
| `display_name` | text | user-entered label |
| `status` | boolean (default true) | only `true` rows are crawled |
| `created_at` | timestamptz | when registered |
| `provider_domain` | text | **resolved from the manifest** after first crawl (`domain`/bppId) |
| `provider_name` | text | **resolved from the manifest** after first crawl (`name`) |

### 3.2 Crawl state — reused, now with a provider link
`V3` adds `provider_domain` to both existing tables so crawl state ties to a provider by its DeDi
identity (not by URL/host guessing):

- `index_crawl_state` (PK `index_url`): `index_digest`, `next_update`, **`provider_domain`**, `last_seen_at`, …
- `catalog_part_state` (PK `part_url`): `catalog_id`, `version`, `digest`, **`provider_domain`**, `last_seen_at`, …

### 3.3 How the link is populated (crawler)
When the crawler resolves a source's manifest it learns `domain` + `name` (`ManifestResolver.Resolved`),
and:
- stamps `provider_domain` on every `index_crawl_state` / `catalog_part_state` row it writes
  (`StateStore.upsertIndexState/upsertPart`, fed `reg.domain()`);
- writes `provider_domain` + `provider_name` back onto the `crawler_source` row
  (`StateStore.updateSourceIdentity`, called from `Crawler.recordSourceIdentity`).

**Why `provider_domain` (the manifest `domain`) and not a UUID:** it's the provider's real, stable
DeDi identity (bppId), present in the manifest, already extracted by the crawler, and it groups a
provider's index + catalogs naturally. It also works for config-mode crawling. `provider_name` is the
authoritative name to display.

---

## 4. UI proxy endpoints (`vite.config.ts`)

| Method | Path | Behaviour |
|--------|------|-----------|
| `POST` | `/api/crawler/sources` | Validate URL → `INSERT INTO crawler_source (dedi_url, display_name)`; on `dedi_url` conflict, re-enable (`status=true`). Returns the row. |
| `GET`  | `/api/crawler/sources` | One row per active source with **name**, **dedi_url**, **catalogs**, **last-synced** — see the join below. |
| `DELETE` | `/api/crawler/sources/{id}` | `UPDATE crawler_source SET status = false` (stops crawling; indexed catalogs remain). |

**The status join (exact, by provider identity):**
```sql
SELECT s.id, s.dedi_url,
       COALESCE(NULLIF(s.provider_name,''), NULLIF(s.display_name,'')) AS name,
       s.provider_domain, s.created_at,
       GREATEST(MAX(i.last_seen_at), MAX(c.last_seen_at)) AS last_synced,
       COUNT(DISTINCT c.catalog_id)                       AS catalogs
FROM   crawler_source s
LEFT   JOIN index_crawl_state  i ON s.provider_domain IS NOT NULL AND i.provider_domain = s.provider_domain
LEFT   JOIN catalog_part_state c ON s.provider_domain IS NOT NULL AND c.provider_domain = s.provider_domain
WHERE  s.status = true
GROUP  BY s.id, s.dedi_url, name, s.provider_domain, s.created_at
ORDER  BY s.created_at;
```
Before the first crawl, `provider_domain` is null → the joins match nothing → the source shows as
**pending** (0 catalogs, no last-synced).

---

## 5. UI views (`reference-discover-ui`)

- **Header nav:** `Discover` (the search app) | `Crawler`. Discover UI lives in `DiscoverView`.
- **CrawlerView** (`components/CrawlerView.tsx`):
  - **Register form** — `dedi.json` URL + optional name → `POST`.
  - **Providers table** — one row per source: **Provider** (name + resolved domain), **dedi.json**,
    **Catalogs**, **Last synced** (green dot = synced, amber = pending), **Remove**.
  - **Auto-refreshes every 5s** so counts / last-synced update on their own.

---

## 6. "Last synced" semantics (important)

`last_synced` = `MAX(last_seen_at)` across the provider's crawl-state rows. Those rows are written
**only when the crawler actually applies a change** (index digest differs → verified → pushed). So:

- It means **"last time this provider's data changed and was synced,"** not "last time it was checked."
- The crawler checks every ~1 min; if nothing changed it writes nothing, so the timestamp holds.
- Edit a catalog → within ~1 min the digest differs → it syncs → "Last synced" jumps to now.

(A "last checked" that ticks every poll would need the crawler to stamp `last_seen_at` on every poll —
not done; out of scope.)

---

## 7. End-to-end flow

1. User registers `https://…/dedi.json` → row in `crawler_source` (`provider_domain` null → pending).
2. Next index poll (~1 min): `DbSourceRegistry` returns the row → crawler resolves the manifest →
   stamps `provider_domain`/`provider_name` on the source and `provider_domain` on crawl-state rows →
   pushes changed catalogs to Discover.
3. UI's 5s poll now shows the real **provider name + domain**, **catalog count**, and **last-synced**.
4. Those catalogs are now searchable in the **Discover** tab.

---

## 8. Notes / limits

- **Auth:** none in the POC (matches the rest of the stack).
- **Remove = disable** (`status=false`); no un-publish path exists, so indexed catalogs stay in
  Discover.
- **No default provider** — the UI never seeds a source; the table starts empty and idles until a
  user registers one.
- **Dead ETag columns** (`manifest_etag`, `index_etag`) remain unused; candidate for cleanup.
