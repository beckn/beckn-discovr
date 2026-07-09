# Beckn Discovr — Functional Correctness & Data-Integrity Failure Analysis

**Date:** 2026-06-17
**Scope:** Functional correctness and data integrity of the discover → query → dispatch pipeline.
**Out of scope:** Infrastructure metrics, dashboards, and alerting — see [`reliability/RELIABILITY_REPORT.md`](../../reliability/RELIABILITY_REPORT.md) for the observability/ops view. This document is its functional complement: it asks *"when does the system silently return wrong, incomplete, stale, mis-routed, or lost data even though every component reports healthy?"*

> **Method.** Findings were produced by a deep code read of the three jobs, then the load-bearing CRITICAL claims were re-verified against the actual source. **Two findings from the first pass were rejected as misreads** (see [Appendix A](#appendix-a--rejected-findings)). Each finding below cites `file:line`. Findings marked **✓verified** were re-read line-by-line during this analysis; the rest are code-traced but not exhaustively re-verified.

---

## 1. Why "functional" failures are the dangerous ones here

Discovr is an **asynchronous, eventually-consistent, dual-store** system:

- Writes land in **PostgreSQL** (system of record) and are then **asynchronously** projected into **Elasticsearch** (search index) — *after* the DB transaction commits.
- Reads (`/discover`) are **fire-and-forget**: the buyer gets a synchronous `ACK`, and the real answer arrives later as an `on_discover` HTTP callback delivered by a *third* service.

The consequence: **almost every failure in this system is silent to the buyer.** A dropped catalog, a stale index, a pruned result, or an undelivered callback all look identical from the outside — the BAP simply receives fewer results, or none, with a `200 ACK` already in hand. There is no synchronous error surface for anything that happens after the ACK. This is the central theme of every finding below.

### Cross-cutting root-cause patterns

| Pattern | Where it bites | Net effect |
|---|---|---|
| **P1 — Commit-then-async-project gap** | Publish: DB commits, then ES indexing runs on `AFTER_COMMIT` listener | DB↔ES divergence; resources in DB but unsearchable, or stale ES docs |
| **P2 — Silent prune/degrade returns 200-empty** | Query routing + response pipeline | Buyer can't distinguish "no matches" from "engine down / over-pruned" |
| **P3 — Criterion silently dropped on fallback** | Query routing when ES absent / spatial unsupported | Buyer's text/geo intent ignored; results match only a subset of the query |
| **P4 — ACK issued before work is durable** | Discover controller: ACK returned, Kafka send async + best-effort | Buyer gets ACK, no callback ever produced; retry suppressed by idempotency cache |
| **P5 — Transient failure → terminal DLT** | Dispatcher + ES-failure paths | Recoverable conditions become permanent loss once retries exhaust |
| **P6 — Trust-the-registry, no identity validation** | Dispatcher callback URL resolution | Wrong-target delivery / cross-BAP data leak on registry data error |
| **P7 — ACK/NACK contract drift** | Discover ACK model | NACK carries no `messageId`; BAP cannot correlate the rejection |

---

## 2. Pipeline-stage failure inventory

Severity reflects **functional/data-integrity impact**, recalibrated after verification (it is not the raw agent output).

### Stage 1 — Ingest & Indexing (`catalog-publish-job`)

#### S1-1 · DB commit succeeds, async ES index fails → permanent DB↔ES divergence — **CRITICAL** (P1)
- **Where:** `step/ElasticIndexStep.java` (`onCatalogPersisted`, AFTER_COMMIT listener) ← `orchestration/CatalogPublishOrchestrator.java` (tx commit then event publish)
- **Trigger:** Any publish where the PG transaction commits but ES indexing later throws (ES down, mapping conflict, embedding failure, executor saturation).
- **Functional impact:** Resources are in the system of record but **never become searchable** (or become searchable late, with no bound). The Kafka offset for the publish is already committed — there is no automatic replay that re-drives indexing from the committed DB state.
- **Silent?** Yes (logged ERROR; buyer/publisher see success).
- **Note:** This is the headline data-integrity risk and is already acknowledged operationally as divergence (RELIABILITY_REPORT I-5). The functional gap is the **absence of a DB-as-source reconciliation/replay path** — the ES-failure topic only retries the *in-flight* documents, not the committed-but-unindexed state.

#### S1-2 · FULL replace: ES delete runs post-commit, not rolled back on failure → orphaned/duplicate docs — **CRITICAL** (P1)
- **Where:** `step/ElasticIndexStep.java` (delete-by-catalog branch), `indexing/BulkIndexService.java` (`deleteByCatalog`)
- **Trigger:** `FULL` replace. DB delete+insert commits; ES `deleteByQuery` then fails *or* the subsequent re-index partially fails.
- **Functional impact:**
  - Delete fails → **stale ES docs survive** alongside newly-indexed ones → duplicate / outdated search hits.
  - Delete succeeds but re-index partially fails → **gaps**: docs purged from ES, only some re-indexed, the rest land in the ES-failure topic with no transactional guarantee they ever return.
- **Silent?** Partially (failures logged + routed to failure topic; main offset committed).

#### S1-3 · FULL replace with zero resources purges the catalog from ES — **HIGH** (P1)
- **Where:** `step/ElasticIndexStep.java` (early-return when batch has no resources, *after* the delete has run)
- **Trigger:** A `FULL` publish that carries only provider/offer data (no resources).
- **Functional impact:** ES delete executes, the "no resources to index" guard returns early → the catalog's resources are **wiped from search** with nothing re-indexed.
- **Silent?** Yes (early return logged at info/debug).

#### S1-4 · Embedding failure at index time → doc indexed without vector → invisible to semantic search — **HIGH** (P1)
- **Where:** `step/ElasticIndexStep.java` (per-item embedding futures; exceptions caught per-item, doc proceeds)
- **Trigger:** Embedding API error/timeout for some items during indexing.
- **Functional impact:** Document is indexed **without `resource_vector`**. Lexical (BM25) search still finds it, but **KNN/semantic search silently skips it forever** — no retry re-embeds it.
- **Silent?** Yes (per-item warn).

#### S1-5 · ES-failure retry exhaustion → DLT publish is best-effort → permanent silent loss — **HIGH** (P5)
- **Where:** `consumer/EsFailureConsumer.java` (route-to-DLQ), the ES failure publisher (`send().whenComplete` logs on error, does not rethrow)
- **Trigger:** Document exhausts retries; final DLQ publish itself fails (broker blip).
- **Functional impact:** Document is logged as "permanent failure" but the DLQ write is unconfirmed → **resource lost with no durable record**.
- **Silent?** Yes (logged only).

#### S1-6 · One bad resource fails the whole catalog batch to DLQ — **MEDIUM**
- **Where:** `step/ValidateStep.java` (validates the full `catalogs[*]` subtree), `consumer/CatalogPublishConsumer.java` (`rejectAndAck`)
- **Trigger:** A single resource in a multi-resource publish fails schema validation.
- **Functional impact:** The **entire batch** (including valid resources) is NACK'd and dropped to the failed topic — no per-resource granularity. Publisher must re-send everything.
- **Silent?** Signaled (NACK to publisher).

#### S1-7 · MERGE: stale `availableAt` locations not removed when a resource's geometry shrinks — **MEDIUM** (P1)
- **Where:** `step/PersistenceStep.java` (location delete scoped to published item ids + catalog)
- **Trigger:** MERGE publish where a resource drops from N locations to fewer.
- **Functional impact:** Old `item_location_collection` rows survive → **spatial discover returns stale/extra locations** for that resource.
- **Silent?** Yes.

#### S1-8 · MERGE offer merge throws → item persisted with stale/partial offers — **MEDIUM**
- **Where:** `step/PersistenceStep.java` (Phase 2 linked-item merge), `step/OfferResolutionStep.java` (Phase 3)
- **Trigger:** `mergeOfferIntoPayload` (RFC 7396) throws mid-batch.
- **Functional impact:** Exception is caught and recorded but the item is still saved → discover returns **incomplete offer data**; partial progress across linked items leaves inconsistent state.
- **Silent?** Signaled in errors list, non-blocking.

#### S1-9 · Cross-catalog offer references a missing resource → offer silently dropped — **MEDIUM**
- **Where:** `step/OfferResolutionStep.java` (Phase 3 resolves `resourceIds` across all catalogs; no match → skip)
- **Trigger:** Offer references a `resourceId` not present in any catalog (ordering: offer published before its resource).
- **Functional impact:** Offer is **never attached to any resource** → undiscoverable. No retry/relink when the resource later arrives.
- **Silent?** Signaled (warn), data lost functionally.

> **Ordering note:** Push partitioning is by `subscriberId` (`CatalogPushService`), which preserves FULL-then-MERGE ordering *per subscriber* only as long as consumer concurrency is one consumer per partition. Cross-catalog offer resolution (Phase 3) reaches across catalogs/partitions and is **not** protected by that ordering — S1-9 is the functional symptom.

---

### Stage 2 — Discover intake: auth, validation, idempotency, ACK contract (`catalog-discover-job`)

#### S2-1 · ACK returned, then async Kafka publish fails → no callback ever produced, retry suppressed — **CRITICAL** ✓verified (P4)
- **Where:** `controller/DiscoveryController.java` — `messageIdDedupCache.put(messageId, TRUE)` is executed **before** `kafkaTemplate.send(...)`; the send is async with errors only logged in `whenComplete`; the `catch` around the send only logs; the method **returns `AckResponse.ack()` unconditionally**.
- **Trigger:** Kafka request-topic publish fails or is rejected after the ACK is computed.
- **Functional impact:** Buyer receives `200 ACK` and waits for an `on_discover` that **will never be generated**. Worse: because the `messageId` was cached *before* the send, **a BAP retry within the dedup TTL is suppressed** and also returns ACK with no work done. End-to-end silent black hole.
- **Silent?** Fully silent to the buyer.
- **Fix direction:** Only populate the dedup cache in the send-success callback; on send failure, evict the cache entry and return a NACK (`NET_*`), or make the publish synchronous on the request path.

#### S2-2 · NACK carries no `messageId` and uses non-canonical error fields → BAP cannot correlate rejections — **HIGH** ✓verified (P7)
- **Where:** `model/AckResponse.java` emits flat `{"status":"NACK","error":{"errorCode":...,"errorMessage":...}}`; `common/BecknFields.java` maps `ERROR_CODE="errorCode"`, `ERROR_MESSAGE="errorMessage"`. `exception/GlobalExceptionHandler.java` + `controller/DiscoveryController.java` build NACKs via `AckResponse.nack(...)`.
- **Contract expected (per `CLAUDE.md`):** ACK/NACK wrapped in `{"message":{"status":...,"messageId":"<uuid echoes context.messageId>"}}`; error object uses `code`/`message`; `error.code` from the canonical `ErrorCode` enum.
- **Functional impact:** For a parseable-but-rejected request (auth fail, schema fail) the BAP gets **no `messageId`** to tie the NACK back to its request, and the field names diverge from the spec the BAPs validate against. This is a live **contract drift** between the shipped model and the documented v2.0 format.
- **Silent?** Signaled (NACK returned) but un-correlatable / schema-divergent.
- **Caveat:** `CLAUDE.md` and the `AckResponse` Javadoc disagree on the intended shape — confirm which is authoritative before remediating. The divergence itself is the finding.

#### S2-3 · Idempotency keyed on `messageId` alone, not `(messageId, transactionId)` — **MEDIUM** ✓verified (P4)
- **Where:** `controller/DiscoveryController.java` — `messageIdDedupCache` keyed solely on `messageId`, TTL-based (Caffeine).
- **Trigger:** A BAP reuses a `messageId` across two distinct transactions within the TTL window (spec uniqueness is on `messageId`+`transactionId`).
- **Functional impact:** The second, legitimately-distinct request is treated as a duplicate → instant ACK, **never processed**, no callback. (UUID collision across BAPs is negligible; deliberate/accidental reuse is the realistic trigger.)
- **Silent?** Yes (logged as duplicate-suppressed).

#### S2-4 · Dedup TTL expiry permits true reprocessing (duplicate callbacks) — **LOW** (P4)
- **Where:** same cache, TTL window.
- **Trigger:** Genuine BAP retry arrives just after TTL expiry.
- **Functional impact:** Request is re-published and re-processed → BAP can receive **two `on_discover` callbacks** for one search (no idempotency key downstream to dedup — see S5-3).

---

### Stage 3 — Query routing & search (`catalog-discover-job`)

The router picks among 7 cases over JSONPath (`J`), Spatial (`G`), Text (`T`). The functional risks cluster around **silent criterion dropping** and **200-empty masking infra failure**.

#### S3-1 · Chain fallback silently drops the Text criterion when ES engine is absent — **HIGH** (P3)
- **Where:** `service/DiscoveryService.java` (chain cases 6 `J+T` / 7 `J+G+T`, `CHAIN_ES_ENGINE_ABSENT` branch)
- **Trigger:** `discovery.spatial.engine=postgresql` or ES engine bean absent, for a query that includes text.
- **Functional impact:** Query degrades to `J` (or `J+G`) — the **text intent is silently ignored**. Buyer receives results filtered by everything *except* what they searched for.
- **Silent?** Warn log only; HTTP 200 with wrong-semantic results.

#### S3-2 · ES `index_not_found` / unknown-field → returns empty as if "no matches" — **HIGH** (P2)
- **Where:** `service/elasticsearch/ElasticsearchTextSearchEngine.java` / `ElasticsearchQueryEngine.java` (catch `index_not_found_exception`, `search_phase_execution_exception`)
- **Trigger:** ES index missing (never created, deleted, wrong alias) or field not mapped.
- **Functional impact:** Infra/mapping failure is **rendered indistinguishable from a legitimate empty result** — buyer gets `200` + empty catalogs.
- **Silent?** Warn log only.

#### S3-3 · Semantic search: empty embedding vector → zero results (no BM25 fallback) — **HIGH** (P2)
- **Where:** `service/elasticsearch/EmbeddingClient.java` (empty/null vector → empty), consumed by `ElasticsearchTextSearchEngine.java`
- **Trigger:** Embedding model returns an empty data array.
- **Functional impact:** A valid text query yields **zero results** instead of degrading to lexical BM25. Silent quality collapse.
- **Silent?** Warn log only.

#### S3-4 · Query enrichment (LLM) failure → raw query used, semantics change silently — **MEDIUM** (P3)
- **Where:** `service/elasticsearch/QueryEnricher.java` (`QUERY_ENRICHER_FAILED`/`_PARSE_FAILED` → fall back to raw query)
- **Trigger:** LLM enrichment provider slow/unreachable/returns unparseable output.
- **Functional impact:** Embedding/search runs on the **un-enriched** query → different (degraded) result set with no signal. (Note: this path *also* has no Micrometer metric — see RELIABILITY_REPORT gap.)

#### S3-5 · Case 7 spatial-condition build failure → geo criterion silently dropped — **MEDIUM** (P3)
- **Where:** `service/DiscoveryService.java` (`QUERY_PATH_FALLBACK` — PG step 2 can't build spatial predicate, degrades to J-only)
- **Trigger:** Unsupported spatial operation in the J+G+T chain's PG step.
- **Functional impact:** Results filtered by JSONPath only; **geo intent ignored**.

#### S3-6 · Chain overfetch capped at `chain.max-ids` → candidate pool silently truncated — **MEDIUM** (P2)
- **Where:** `service/DiscoveryService.java` (`CHAIN_TRUNCATED_BY_CAP`)
- **Trigger:** `limit × overfetch-factor > chain.max-ids` (e.g., large limit).
- **Functional impact:** ES step-1 candidate set is capped before PG step-2 filtering → **valid matches beyond the cap never considered**; result completeness silently reduced. (Info log only.)

#### S3-7 · PostgreSQL assembler silently skips malformed rows/offers — **MEDIUM**
- **Where:** `service/postgresql/PostgreSQLAssembler.java` (row missing `catalog_id` / null payload / JSON parse error → skip; malformed offer → skip)
- **Trigger:** Corrupt/legacy/partially-written row or offer JSON.
- **Functional impact:** **Silent per-row data loss** — buyer receives fewer resources/offers than exist, with no indication rows were dropped.

#### S3-8 · Known geo limitation: PG stores one location per provider → multi-location collapse — **MEDIUM** (accepted)
- **Where:** PostgreSQL geo path (J+G / Offer+G / chain).
- **Status:** **Known and accepted** — user decided not to fix (memory: `finding_pg_multilocation_geo`). Documented here for completeness: non-last locations are missed by PG-side geo.

#### S3-9 · Single-engine / chain query timeout → request fails, partial results discarded — **LOW** (signaled)
- **Where:** `service/DiscoveryService.java` (`QUERY_TIMEOUT`, parallel/chain timeouts)
- **Functional impact:** Timeout is signaled (error → no callback / DLT), but any partial results already retrieved are thrown away rather than returned best-effort.

---

### Stage 4 — Response assembly & pipeline (`catalog-discover-job`)

`CatalogPipeline` runs: (1) schema-context filter → (2) dedup offers → (3) filter resources by offer refs → (4) filter offers by resource ids → (5) drop empty catalogs. Each step can **discard valid data and yield an empty `on_discover`** with no error.

#### S4-1 · Schema-context filter does exact `@type` fragment match → over-prunes valid resources — **HIGH** (P2)
- **Where:** `service/response/CatalogProcessor.java` (`matchesSchema`, exact `equals` on the context fragment); driven by `CatalogPipeline.java` step 1.
- **Trigger:** Request supplies a schema-context fragment (e.g. `…#MenuItem`) and a matching resource has a different-but-valid `@type` (e.g. `Item`), or is missing `@context`/`@type`.
- **Functional impact:** Resource **dropped entirely** despite matching the search → empty catalogs to buyer. The exact-match strictness has no fuzzy/base-URL fallback.
- **Silent?** Debug log only.

#### S4-2 · Schema filter applied twice on the NLWeb path → compounded over-pruning — **MEDIUM** (P2)
- **Where:** `service/DiscoveryService.java` (calls pipeline with `schemaPreFiltered=false` for NLWeb) + `CatalogPipeline.java` step 1.
- **Trigger:** NLWeb text search with schema context present (`appliesSchemaFilter()==false`).
- **Functional impact:** NLWeb's own filtering plus the exact-match re-filter (S4-1) compound → more aggressive pruning than intended.

#### S4-3 · Offer↔resource cross-filtering (steps 3–4) empties catalogs on broken refs — **HIGH** (P2)
- **Where:** `service/response/CatalogProcessor.java` (`filterResourcesByOfferReferences`, `filterOffersByResourceIds`).
- **Trigger:** Offers reference resource ids absent from the catalog (cross-query join mismatch, enricher adding offers after resources were pruned).
- **Functional impact:** Step 3 can strip all resources; step 4 can strip all offers; step 5 then removes the now-empty catalog. Buyer gets **empty results even though both resources and offers matched** — they just didn't cross-reference. Depending on `discovery.filter.discard-catalogs-without-offers`, otherwise-valid resource-only catalogs are also dropped.
- **Silent?** Debug log only.

#### S4-4 · Empty-catalog removal makes "pruned to nothing" identical to "no matches" — **MEDIUM** (P2)
- **Where:** `service/response/CatalogPipeline.java` step 5; `service/DiscoveryService.java` `buildResponse`.
- **Functional impact:** After any of S4-1..S4-3 empties the catalogs, the buyer receives a well-formed empty `on_discover` — **no way to tell over-pruning from a genuine zero-result search.**

#### S4-5 · Response validation failure → silent downgrade to empty response — **MEDIUM** (P2)
- **Where:** `service/response/ResponseProcessor.java` (`validateResponse` fail → empty fallback).
- **Trigger:** Assembled response fails internal validation (null context, malformed catalog).
- **Functional impact:** Buyer gets empty catalogs instead of a signal that assembly broke. (Warn log only.)

#### S4-6 · `on_discover` correlation (`requestDigest`/`inReplyTo`) population — **MEDIUM → verify**
- **Where:** `service/response/ResponseProcessor.java`; `model/DiscoverResponse.java` (`RequestDigest` defined).
- **Reported risk:** Empty/normal responses may ship without the request-correlation field populated, so the BAP can't tie the callback to its `messageId`.
- **Status:** Flagged by analysis but **not line-verified** in this pass — the response-context build path should be confirmed before acting. (Memory notes `inReplyTo` was renamed to `requestDigest` in the v2.1 migration, so naming drift is plausible.)

---

### Stage 5 — Callback delivery (`response-dispatcher`)

The dispatcher resolves the BAP URL (DeDi registry or static), SSRF-validates, signs, and POSTs `on_discover`. Consumer ack semantics are **correct** (process → ack; on failure → DLT → ack; DLT-publish failure → rethrow, offset uncommitted — `EventListener.java:90-129` ✓verified). The functional risks are in **routing correctness, transient-vs-terminal handling, and duplicate/auth integrity.**

#### S5-1 · Transient delivery failure becomes terminal DLT once HTTP retries exhaust — **HIGH** ✓verified (P5)
- **Where:** `service/HttpService.java` (Spring `@Retryable` on `RestClientException`, `@Recover` → `CallbackDeliveryException`) → `messaging/consumer/EventListener.java` catch → `sendToDlt` → ack.
- **Trigger:** BAP down/slow beyond the bounded retry budget (default ~3 attempts, 1s→10s backoff).
- **Functional impact:** A **temporarily** unreachable BAP results in the response being routed to DLT and the offset committed — **no Kafka-level redelivery**. If the DLT isn't actively drained, the buyer's results are lost. Recovery time exceeding the retry budget = permanent loss.
- **Silent?** Signaled to DLT; silent to buyer.

#### S5-2 · Registry resolves to wrong/empty URL → wrong-target delivery or silent drop — **HIGH/CRITICAL** (P6)
- **Where:** `service/HttpService.java` (`resolveTargetUrl` → `becknAuth.getRegistryEntry(subscriberId, recordId)`; blank-URL → `IllegalArgumentException` → DLT).
- **Trigger:** DeDi registry returns a stale/incorrect `subscriberUrl()` for the `(subscriberId, recordId)` pair, or an empty/blank URL.
- **Functional impact:**
  - Wrong URL → **BAP-A's `on_discover` delivered to BAP-B** (cross-BAP data leak; BAP-A gets nothing). There is **no validation that the returned URL belongs to the requested identity** — the dispatcher trusts the registry absolutely.
  - Blank URL → callback dropped to DLT (permanent loss for that BAP).
- **Silent?** Wrong-target is silent (logged at info as "resolved"); blank-URL is signaled to DLT.

#### S5-3 · At-least-once retries with no idempotency key → duplicate callbacks — **HIGH** (P4/P5)
- **Where:** `service/HttpService.java` (retry path; no `Idempotency-Key`/dedup header on the POST).
- **Trigger:** Delivery succeeds at the BAP but the client times out / sees a transient error → retry re-POSTs the same payload.
- **Functional impact:** BAP receives the **same `on_discover` 2–3×**; nothing in the payload/headers lets the BAP dedup reliably (spec doesn't mandate it). Combined with S2-4 (request re-processing), duplicates can multiply.

#### S5-4 · Static-callback mode routes *all* callbacks to one URL → broadcast mis-delivery — **HIGH** (P6)
- **Where:** `service/HttpService.java` (static-callback branch bypasses registry entirely).
- **Trigger:** `static-callback.enabled=true` with a misconfigured/stale URL.
- **Functional impact:** **Every** BAP's callback goes to the single configured endpoint regardless of identity — no runtime validation. Intended for dev/single-tenant; a config slip in prod mis-delivers everything.

#### S5-5 · SSRF validator false-positives block legitimate callbacks — **MEDIUM** (P5)
- **Where:** `service/HttpService.java` (`validateCallbackUrl` → `InetAddress.getByName`; loopback/link-local/site-local or `UnknownHostException` → reject; not retryable).
- **Trigger:** Legit BAP behind private addressing, or transient DNS failure.
- **Functional impact:** Legitimate callback **rejected → DLT** with no DNS retry → buyer gets nothing.

#### S5-6 · DNS TOCTOU between SSRF check and HTTP connect (SSRF bypass) — **MEDIUM (security)**
- **Where:** `service/HttpService.java` (resolve-then-connect; acknowledged TODO comment).
- **Trigger:** Attacker-controlled DNS rebinds host between the validation lookup and the actual connect.
- **Functional impact:** Callback could be delivered to an internal/loopback target after passing the public-IP check. Needs IP-pinned `ClientHttpRequestFactory` to close.

#### S5-7 · Signing disabled by config → unverifiable callback payloads — **MEDIUM (security)**
- **Where:** `service/HttpService.java` (signing gated on `signing.enabled`; default disabled per `application.yml`).
- **Functional impact:** BAP receives an `on_discover` with **no Beckn HTTP Signature** → cannot authenticate it; either rejects (no delivery) or accepts unverified (security gap).

#### S5-8 · Envelope parse failure → immediate DLT even if transiently corrupt — **MEDIUM** (P5)
- **Where:** `messaging/consumer/EventListener.java:62-82` (unparseable envelope short-circuits to DLT + ack). ✓verified
- **Functional impact:** A malformed/partially-written envelope is dropped to DLT on first sight with no retry — fine for truly-bad data, lossy for transient corruption.

#### S5-9 · `409 AckNoCallback` treated as terminal success — **LOW/MEDIUM**
- **Where:** `service/HttpService.java` (HTTP 409 → success, increment, no retry).
- **Functional impact:** Correct per spec (409 = "ack, don't call back"), but a BAP returning 409 while *temporarily* not ready loses that callback permanently with no re-queue.

---

## 3. Severity-ranked master table

| ID | Title | Stage | Severity | Silent? | Pattern |
|---|---|---|---|---|---|
| S1-1 | DB commit then async ES index fail → divergence | Ingest | CRITICAL | Yes | P1 |
| S1-2 | FULL replace ES delete/​re-index partial fail | Ingest | CRITICAL | Partial | P1 |
| S2-1 | ACK then Kafka send fail → no callback, retry suppressed | Intake | CRITICAL ✓ | Yes | P4 |
| S5-2 | Registry wrong/empty URL → mis-delivery / drop | Dispatch | HIGH/CRIT | Partial | P6 |
| S1-3 | FULL replace, zero resources → ES purge | Ingest | HIGH | Yes | P1 |
| S1-4 | Index-time embedding fail → no vector → semantic-invisible | Ingest | HIGH | Yes | P1 |
| S1-5 | ES-failure DLT publish best-effort → loss | Ingest | HIGH | Yes | P5 |
| S2-2 | NACK missing `messageId` / non-canonical fields | Intake | HIGH ✓ | Signaled | P7 |
| S3-1 | Chain fallback drops Text criterion | Query | HIGH | Yes | P3 |
| S3-2 | ES index-not-found → 200-empty | Query | HIGH | Yes | P2 |
| S3-3 | Empty embedding → zero results, no BM25 fallback | Query | HIGH | Yes | P2 |
| S4-1 | Schema exact `@type` match over-prunes | Response | HIGH | Yes | P2 |
| S4-3 | Offer↔resource cross-filter empties catalogs | Response | HIGH | Yes | P2 |
| S5-1 | Transient delivery fail → terminal DLT | Dispatch | HIGH ✓ | Partial | P5 |
| S5-3 | Retries w/o idempotency key → duplicate callbacks | Dispatch | HIGH | Signaled | P4 |
| S5-4 | Static-callback broadcast mis-delivery | Dispatch | HIGH | Yes | P6 |
| S1-6 | One bad resource fails whole batch | Ingest | MEDIUM | Signaled | — |
| S1-7 | MERGE stale locations | Ingest | MEDIUM | Yes | P1 |
| S1-8 | MERGE offer-merge throw → partial offers | Ingest | MEDIUM | Signaled | — |
| S1-9 | Cross-catalog offer → missing resource → dropped | Ingest | MEDIUM | Signaled | — |
| S2-3 | Idempotency keyed on `messageId` only | Intake | MEDIUM ✓ | Yes | P4 |
| S3-4 | LLM enrichment fail → raw query | Query | MEDIUM | Yes | P3 |
| S3-5 | Case-7 spatial build fail → geo dropped | Query | MEDIUM | Yes | P3 |
| S3-6 | Chain overfetch cap → truncated candidates | Query | MEDIUM | Yes | P2 |
| S3-7 | Assembler skips malformed rows/offers | Query | MEDIUM | Yes | — |
| S3-8 | PG multi-location collapse (accepted) | Query | MEDIUM | Yes | — |
| S4-2 | Double schema filter on NLWeb path | Response | MEDIUM | Yes | P2 |
| S4-4 | Empty-catalog removal masks over-pruning | Response | MEDIUM | Yes | P2 |
| S4-5 | Response validation fail → silent empty | Response | MEDIUM | Yes | P2 |
| S4-6 | `requestDigest` correlation (verify) | Response | MEDIUM? | Yes | P7 |
| S5-5 | SSRF false-positive blocks legit callback | Dispatch | MEDIUM | Yes | P5 |
| S5-6 | DNS TOCTOU SSRF bypass | Dispatch | MEDIUM(sec) | Yes | — |
| S5-7 | Signing disabled → unverifiable payload | Dispatch | MEDIUM(sec) | — | — |
| S5-8 | Envelope parse fail → immediate DLT | Dispatch | MEDIUM | Partial | P5 |
| S2-4 | Dedup TTL expiry → duplicate processing | Intake | LOW | Yes | P4 |
| S3-9 | Query timeout discards partials | Query | LOW | Signaled | — |
| S5-9 | 409 AckNoCallback terminal | Dispatch | LOW/MED | Signaled | — |

---

## 4. Prioritized remediation themes

1. **Close the commit-then-project gap (S1-1/-2/-3/-4).** The single highest-leverage fix: a **DB-as-source reconciliation job** that re-drives ES indexing for catalogs whose DB `updated_at` is newer than their ES doc (or that are missing from ES). This converts every P1 silent divergence from "permanent" to "self-healing within reconciliation interval." Pair with making FULL-replace ES delete+reindex atomic-ish (index-then-swap-alias rather than delete-then-index).

2. **Make the discover ACK honest (S2-1).** Only cache the `messageId` and return ACK *after* the Kafka send is confirmed; on send failure, evict and NACK. This removes the worst black-hole.

3. **Distinguish "empty" from "broken" on the read path (P2: S3-2, S3-3, S4-1, S4-4, S4-5).** Carry a degraded/partial signal (e.g. a `meta` flag or distinct error callback) when results are empty *because* an engine failed or the pipeline pruned everything — so buyers and ops can tell the two apart. At minimum, raise these from DEBUG/WARN logs to counters (ties into RELIABILITY_REPORT gaps).

4. **Never silently drop a query criterion (P3: S3-1, S3-5).** When ES/spatial is unavailable for a multi-criterion query, fail loud (NACK / error callback) rather than returning subset-matched results that look authoritative.

5. **Harden callback routing (P6: S5-2, S5-4).** Validate that the registry-returned URL's identity matches the requested `(subscriberId, recordId)`; gate static-callback behind a non-prod profile guard.

6. **Add delivery idempotency (S5-3) and a DLT drain/replay (S1-5, S5-1).** An `Idempotency-Key` (e.g. `transactionId:messageId`) on the callback POST lets BAPs dedup; an automated DLT replay turns P5 terminal losses back into retries.

7. **Resolve the ACK/NACK contract drift (S2-2).** Reconcile `AckResponse` with the documented v2.0 envelope (`message` wrapper, `messageId`, `code`/`message`) — or update the docs if the flat form is intentional. Today they disagree.

---

## Appendix A — Rejected findings

These were raised in the first analysis pass and **rejected on re-verification** — recorded so they are not re-litigated:

- **"Discover consumer acks before publishing the response."** *False.* `consumer/DiscoveryEventConsumer.java:142-147,209-232` deliberately moves `acknowledgment.acknowledge()` **inside** the `whenComplete` success branch; a broker rejection leaves the offset uncommitted for the container error handler to retry/DLT. The code and its Javadoc are explicit.
- **"Dispatcher acks before delivery (zero-delivery)."** *False.* `messaging/consumer/EventListener.java:90,95` calls `processMessage(...)` first and `ack.acknowledge()` only on success; failures go to DLT then ack, and a DLT-publish failure rethrows so the offset is **not** committed.

---

*Generated from a deep code read of `catalog-publish-job`, `catalog-discover-job`, and `response-dispatcher`. Load-bearing CRITICALs re-verified against source (marked ✓verified). For instrumentation/alerting to detect these conditions in production, cross-reference `reliability/RELIABILITY_REPORT.md`.*
