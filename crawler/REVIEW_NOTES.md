# Crawler POC — Review Notes (fixes to take up later)

Review date: 2026-07-21. Scope: static review of the crawler module against the design doc
(`docs/decentralized-catalog/decentralized-catalog-crawler-poc.md`).

**Overall:** faithful to §5.4, clean component split (§5.1), follows Discovr conventions
(constructor injection, parameterized SQL, config-driven, structured logging), good tests
incl. a Testcontainers integration test (fresh / unchanged / modified / tampered). None of the
below blocks the POC — they are follow-ups.

## MEDIUM

- [ ] **M1 — Size cap enforced after full buffering.** `CrawlerHttpClient.get()` uses
  `BodyHandlers.ofByteArray()` then checks `body.length > maxBytes`; the whole body is read into
  memory before the cap triggers, so it doesn't protect against OOM. Use a bounded/streaming body
  handler that aborts once the limit is crossed. (`http/CrawlerHttpClient.java`)

- [ ] **M2 — SSRF surface (deferred by design, flag loudly).** The crawler GETs arbitrary URLs
  from the manifest/index and follows redirects (`Redirect.NORMAL`). A malicious manifest can aim
  it at internal addresses (e.g. link-local metadata, cluster services). Explicit §2 non-goal, but
  this is the #1 thing to fix before running against untrusted providers; redirects amplify it.
  Add private-address/redirect-host allowlisting. (`http/CrawlerHttpClient.java`)

- [ ] **M3 — `catalog_id` written but never read → no removal/RETIRED cleanup.** The column was
  added so the crawler could find a catalog's part rows when it drops a part or goes RETIRED. It's
  populated but never queried, so a RETIRED catalog's pushed data is never un-indexed and its
  `catalog_part_state` rows orphan; same when a multi-part catalog drops a part. Matches the OQ-1
  "log and skip" fallback — confirm it's a conscious gap. (`crawl/Crawler.java`, `state/StateStore.java`)

## LOW

- [ ] **L1 — ETag columns are dead.** `manifest_etag`/`index_etag` exist and `CrawlerHttpClient`
  captures the ETag, but nothing writes or reads them, and `cacheBust=true` (default) makes
  conditional GETs impossible anyway. Wire the ETag optimization or comment the columns as deferred.

- [ ] **L2 — `ManifestResolver` takes `files[0]` blindly.** Not filtered by
  `registry == "beckn-catalogs"` or `state == "live"`; a multi-registry manifest would pick wrong.
  (`crawl/ManifestResolver.java`)

- [ ] **L3 — `version` is a primitive `long`.** A record missing `version` deserializes to `0`,
  which could spuriously trip the rollback guard against a stored positive version. Use boxed
  `Long` + explicit null handling. (`model/FeedModels.java`)

- [ ] **L4 — Verify the push path.** `pushEndpoint` is env-driven and was set to
  `/beckn/catalog/push`; the controller maps `/catalog/push`. Confirm against the publish job's
  context path — a 404 is treated as non-ack and retried every pass forever.

- [ ] **L5 — Push is unsigned.** No Beckn signature is sent. Current `/catalog/push` doesn't verify
  one, so it works; if that endpoint ever requires Beckn auth, the crawler must sign.

## Nits

- [ ] `StateStore.findPart` reads `source_updated_at` via `getString` though stored as `TIMESTAMPTZ`
  (harmless — value unused in logic).
- [ ] `FeedbackLog.record` isn't synchronized (fine while `runPass` is single-threaded).

## Logging review (see also the "log volume" observations)

- [ ] **LOG1 — Expected outcomes logged at WARN + written to the feedback log.** A
  `SKIP_NON_PUBLIC` and a `RETIRE` are normal, expected results, but each emits a WARN
  (`crawler.feedback`) **and** a line in the provider-facing feedback log — on *every* pass where
  the index changed. The feedback log is meant for provider-actionable rejects (e.g. digest
  mismatch, schema fail), not "this catalog is private / retired as intended." Consider: log
  non-public/retired at INFO/DEBUG and keep them out of the feedback file.

- [ ] **LOG2 — Per-catalog INFO logging doesn't scale.** When the index changes, the crawler
  iterates every record and logs one line per catalog — including `crawler.catalog.unchanged`
  (INFO) for all the catalogs that did NOT change. A provider with N catalogs that publishes one
  change produces ~N INFO lines per pass. Fine at POC scale (3 catalogs), heavy at thousands.
  Consider aggregating (`unchanged=9,997`) instead of one line each, or drop unchanged to DEBUG.
