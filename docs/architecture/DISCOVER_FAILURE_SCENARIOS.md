Discover Service — Functional Failure Scenarios

A stage-by-stage map of where the discover pipeline can functionally break

17 June 2026

# How to read this document

This document walks the Discover ingest → intake → query → assemble → deliver pipeline stage by stage and lists the points where the system can produce a functionally wrong outcome — a catalog that is dropped, a result that is over-pruned, a query criterion that is silently narrowed, or a callback that never arrives.

The focus is functional correctness (wrong/missing/duplicate results from the system's own logic), not infrastructure or load failures (a broker being down, a pod running out of memory). Where a logic path depends on infrastructure in a way that silently loses data, it is included — because the user-visible result is a functional one.

Every scenario was re-verified against the current source code. Findings from an earlier draft that the code has since fixed, or that only occur under configurations this deployment never runs, were removed — see "Removed after verification" at the end. Deployment assumption: `discovery.spatial.engine=elasticsearch` (the Elasticsearch engine is always present).

Each scenario is written in the same shape:

- **Scenario** — the trigger: the input or condition that sets it off
- **Why it fails** — the root mechanism in the design/code
- **Impact** — what the buyer (BAP), publisher, or operator actually observes
- **Severity** — Critical (silent data loss / black hole) · High (wrong / missing / duplicated results) · Medium (edge-case or partial) · Low (rare / cosmetic / self-correcting)

**The recurring theme.** Discover is asynchronous: the buyer receives an instant ACK, and the real answer arrives later as an `on_discover` callback delivered by a separate service. So the most dangerous failures are silent — the caller has a `200 ACK` in hand, the operator sees no error, and the result simply never arrives, arrives empty, or arrives wrong. These are flagged throughout and summarised at the end.

# The pipeline at a glance

```
Ingest (separate flow):
  Catalg ──> Catalog Publish Job ──> PostgreSQL (commit) ──> async ES indexing

Discover (request/response):
  BAP ──POST /discover──> Discover Intake ──> Kafka request topic
         (sync ACK) <──┘                            │
                                                     ▼
                                          Discovery Consumer (async)
                                                     │
                                                     ▼
                            Query Engines (PostgreSQL / Elasticsearch / NLWeb)
                                                     │
                                                     ▼
                            Response Pipeline (schema filter → dedup → cross-filter → prune)
                                                     │
                                                     ▼
                            Kafka response topic ──> Response Dispatcher ──> POST on_discover ──> BAP
```

# Stage 1 — Ingest & Indexing (catalog-publish-job)

Catalog data commits to PostgreSQL, then is asynchronously projected into Elasticsearch after the transaction commits. Its defining risk is the gap between "committed to the database" and "searchable in the index" — the two stores drift, and the drift is silent.

## 1.1 Catalog saved to the database but never searchable

- **Scenario:** A catalog is committed to PostgreSQL, then Elasticsearch indexing fails (ES down, mapping conflict, executor saturation).
- **Why it fails:** Indexing runs on an after-commit listener, after the database transaction has committed and the Kafka offset is already acknowledged. A batch-level indexing failure is only logged — there is no job that re-drives indexing from the committed database state (`ElasticIndexStep`).
- **Impact:** The resource is in the system of record but permanently absent from search. The publisher sees success.
- **Severity:** Critical

## 1.2 FULL replace leaves duplicate or orphaned search docs

- **Scenario:** A FULL replace commits its database delete+insert, then the Elasticsearch delete or the re-index partially fails.
- **Why it fails:** The post-commit ES delete failure is caught and swallowed, so re-index proceeds anyway; a partial re-index sends only some documents to the retry topic (`ElasticIndexStep`).
- **Impact:** Stale ES documents survive next to the new ones (duplicate / outdated hits), or gaps appear where some resources never re-index.
- **Severity:** Critical

## 1.3 FULL replace with no resources wipes the database, orphans the index

- **Scenario:** A FULL replace carries only provider/offer data with no resources.
- **Why it fails:** The persistence step runs the FULL delete unconditionally, but the indexing step early-returns before touching Elasticsearch when the batch has no resources (`PersistenceStep`, `ElasticIndexStep`).
- **Impact:** The database loses the resources while Elasticsearch keeps them — search returns resources that no longer exist in the system of record.
- **Severity:** High

## 1.4 Embedding failure makes a resource invisible to semantic search

- **Scenario:** The embedding provider errors or times out for some items during indexing.
- **Why it fails:** Each item's embedding runs independently; an embedding exception is caught per-item and the document is indexed without its vector. Nothing re-embeds it later (`ElasticIndexStep`).
- **Impact:** Keyword search still finds the resource, but semantic/KNN search skips it forever.
- **Severity:** High

## 1.5 A "permanently failed" document is never actually recorded

- **Scenario:** A document exhausts its indexing retries and the final write to the dead-letter topic itself fails (a broker blip).
- **Why it fails:** The dead-letter publish is fire-and-forget — it logs on failure but does not rethrow, while the offset is committed regardless (`EsFailureConsumer`).
- **Impact:** The document is lost with no durable trace that it was lost.
- **Severity:** High

## 1.6 One bad resource fails the entire publish batch

- **Scenario:** A single resource in a multi-resource publish fails schema validation.
- **Why it fails:** Validation runs over the whole message in one pass and throws on any failure; the consumer then rejects the entire raw message to the failed topic (`ValidateStep`).
- **Impact:** Valid resources in the same batch are dropped too; the publisher must re-send everything. (Signalled to the publisher.)
- **Severity:** Medium

## 1.7 An offer-merge error silently drops the item

- **Scenario:** The RFC 7396 offer merge throws mid-batch for one item.
- **Why it fails:** The merge is wrapped per-item; on exception the item is recorded as an error and excluded from persistence, while the rest of the batch still commits (`PersistenceStep`).
- **Impact:** That item silently disappears from the publish (it is dropped, not persisted with partial offers); the rest succeeds.
- **Severity:** Medium

## 1.8 A cross-catalog offer is dropped when its resource isn't published yet

- **Scenario:** An offer references a resource in another catalog that hasn't been published.
- **Why it fails:** Offer resolution looks across catalogs; an unmatched resource id is skipped with no linkage and no relink when the resource later arrives (`OfferResolutionStep`).
- **Impact:** The offer is never attached to any resource — undiscoverable.
- **Severity:** Medium

# Stage 2 — Discover Intake (catalog-discover-job)

The synchronous front door: authenticate, validate the schema, dedup by message id, hand the request to Kafka, return an ACK. Its defining risk is the gap between "ACK sent" and "work durably queued" — once the ACK is out, there is no error path back to the buyer.

## 2.1 Valid ACK returned, but the callback is never produced

- **Scenario:** The buyer receives a `200 ACK`, then the Kafka publish of the request fails.
- **Why it fails:** The message id is written to the dedup cache before the asynchronous Kafka send; a send failure is only logged and does not evict the cache, and the method returns ACK unconditionally (`DiscoveryController`).
- **Impact:** The buyer waits for an `on_discover` that will never come — and a retry within the dedup window is suppressed as a duplicate. An end-to-end black hole.
- **Severity:** Critical

## 2.2 A rejected request gives the buyer nothing to correlate it with

- **Scenario:** A request is rejected for auth or schema reasons and a NACK is returned.
- **Why it fails:** The NACK is emitted in a flat shape with no message id and uses non-canonical error field names, diverging from the documented v2.0 envelope (`AckResponse`).
- **Impact:** The buyer cannot tie the rejection back to its request, and the field names don't match the spec it validates against.
- **Severity:** High

## 2.3 Two genuinely different requests collapse into one

- **Scenario:** A buyer reuses a message id across two distinct transactions within the dedup window.
- **Why it fails:** Idempotency is keyed on the message id alone, not on message id plus transaction id (`DiscoveryController`).
- **Impact:** The second, legitimately distinct request is ACK'd as a duplicate and never processed — no callback.
- **Severity:** Medium

## 2.4 One search produces two callbacks

- **Scenario:** A genuine retry arrives just after the dedup entry expires (default 60s) or is evicted under load.
- **Why it fails:** Idempotency relies entirely on a short-lived in-memory cache; once the entry is gone the retry is reprocessed as new, with no downstream dedup (`DiscoveryController`).
- **Impact:** The buyer receives two `on_discover` callbacks for one search.
- **Severity:** Low

# Stage 3 — Query Routing & Search (catalog-discover-job)

Routes the request among JSONPath, spatial, and text-search combinations across PostgreSQL, Elasticsearch, and NLWeb. This stage is materially hardened — semantic, embedding, and enrichment failures now surface as `503` errors rather than silent empty results (see "Removed after verification"). The risks that remain are about empty-vs-broken ambiguity and silent truncation.

## 3.1 A missing index reads as "no matches"

- **Scenario:** The Elasticsearch index is missing (never created, deleted, or wrong alias).
- **Why it fails:** An index-not-found error is caught and returned as an empty result; all other ES errors do propagate as failures (`ElasticsearchTextSearchEngine`).
- **Impact:** A genuine setup/infra problem looks identical to a legitimate zero-result search — `200` with empty catalogs.
- **Severity:** High

## 3.2 Matches beyond the candidate cap are never considered

- **Scenario:** A query asks for a large result limit.
- **Why it fails:** The text-search step's candidate pool is capped (`limit × overfetch`, bounded by a max-ids ceiling) before the PostgreSQL filtering step runs (`DiscoveryService`).
- **Impact:** Valid matches beyond the cap are silently excluded; completeness is reduced. (Signalled via log and metric.)
- **Severity:** Medium

## 3.3 Malformed database rows silently disappear from results

- **Scenario:** A row has a missing catalog id, a null payload, or unparseable JSON.
- **Why it fails:** The assembler skips such rows, counting them as "skipped" (`PostgreSQLAssembler`).
- **Impact:** The buyer receives fewer resources/offers than exist, with no caller-facing indication that rows were dropped.
- **Severity:** Medium

## 3.4 A multi-location provider is matched on only one location

- **Scenario:** A JSONPath + geo (J+G) query targets a provider that has several locations.
- **Why it fails:** The PostgreSQL geo path stores one location per provider, and J+G always runs through PostgreSQL regardless of the configured spatial engine. (Known and accepted — not fixed by decision.)
- **Impact:** Non-primary locations are missed by PostgreSQL-side geo on J+G queries.
- **Severity:** Medium (accepted)

# Stage 4 — Response Assembly & Pipeline (catalog-discover-job)

After every query the raw catalogs pass through a pipeline: schema-context filter → dedup offers → filter resources by offer references → filter offers by resource ids → drop empty catalogs. Its defining risk is that any step can discard valid data and yield an empty `on_discover` indistinguishable from a genuine no-match.

## 4.1 A strict @type check drops matching resources

- **Scenario:** The request supplies a schema-context fragment and a matching resource carries a different-but-valid type, or no type at all.
- **Why it fails:** The schema filter does an exact match on the context base and type fragment, with no fuzzy or base-URL fallback; resources with no attributes/context are also dropped (`CatalogProcessor`).
- **Impact:** A resource that genuinely matches the search is dropped → empty catalogs to the buyer.
- **Severity:** High

## 4.2 Broken offer↔resource references empty out a whole catalog

- **Scenario:** Offers reference resource ids that aren't present in the catalog (a cross-query join mismatch).
- **Why it fails:** The cross-filter steps strip all resources, then all offers, and the now-empty catalog is removed by the final step (`CatalogProcessor`, `CatalogPipeline`).
- **Impact:** The buyer gets empty results even though both resources and offers matched — they simply didn't cross-reference.
- **Severity:** High

## 4.3 "Pruned to nothing" is indistinguishable from "no matches"

- **Scenario:** Any of the filters above prunes a catalog down to empty.
- **Why it fails:** An emptied catalog set returns the same empty `on_discover` (empty catalogs array) as a genuine zero-result query; no flag marks over-pruning (`ResponseProcessor`).
- **Impact:** Neither the buyer nor the operator can tell an over-pruned result from a true no-match.
- **Severity:** Medium

## 4.4 One malformed catalog downgrades the whole response to empty

- **Scenario:** A single assembled catalog or resource fails internal validation.
- **Why it fails:** Response validation requires every catalog/resource to pass; if any fails, the entire response is replaced with an empty one (`ResponseProcessor`).
- **Impact:** One bad catalog wipes all the otherwise-valid results. (Logged as a warning, silent to the buyer.)
- **Severity:** Medium

## 4.5 The callback carries no dedicated correlation field

- **Scenario:** Any `on_discover` is assembled (empty or populated).
- **Why it fails:** The request-correlation object exists in the model but is unused dead code; the response message contains only catalogs, and correlation rides on the echoed context message id / transaction id (`DiscoverResponse`).
- **Impact:** Low in practice — the buyer can still correlate via the echoed context — but the spec's dedicated correlation field is absent.
- **Severity:** Low

# Stage 5 — Callback Delivery (response-dispatcher)

Resolves the BAP callback URL (DeDi registry or static config), SSRF-validates it, optionally signs it, and POSTs the `on_discover`. Its defining risk is silent dead-lettering — the buyer already has an ACK, so a failure here is invisible to them.

## 5.1 A wrong registry entry delivers the callback to the wrong BAP

- **Scenario:** The DeDi registry returns a stale or incorrect URL for the requested identity, or a blank URL.
- **Why it fails:** The resolved URL is used as-is after only a blank/null check — there is no verification that it belongs to the requested subscriber (`HttpService`).
- **Impact:** One BAP's results are delivered to another (cross-BAP data leak); a blank URL is dead-lettered and lost. The dispatcher trusts the registry absolutely.
- **Severity:** Critical

## 5.2 A temporarily-down BAP loses its results permanently

- **Scenario:** The BAP is unreachable for longer than the bounded retry budget (~3 attempts, 1s→10s backoff).
- **Why it fails:** Once retries exhaust, the response is routed to the dead-letter topic and the offset is committed — there is no Kafka-level redelivery (`HttpService`, `EventListener`).
- **Impact:** A BAP that recovers after the retry window loses its callback unless the dead-letter topic is actively drained.
- **Severity:** High

## 5.3 At-least-once retries send duplicate callbacks

- **Scenario:** Delivery succeeds at the BAP but the client times out, so the request is retried.
- **Why it fails:** The callback POST carries no idempotency key, and the same payload is re-sent unchanged (`HttpService`).
- **Impact:** The BAP receives the same `on_discover` 2–3× with nothing to reliably dedup on.
- **Severity:** High

## 5.4 Static-callback mode broadcasts every callback to one URL

- **Scenario:** Static-callback mode is enabled with a misconfigured or stale URL.
- **Why it fails:** Static-callback bypasses the registry entirely and routes all callbacks to the single configured URL regardless of identity; it is gated only by an env toggle, not a deployment profile (`HttpService`).
- **Impact:** A config slip in production mis-delivers every BAP's callback to one endpoint.
- **Severity:** High

## 5.5 A legitimate callback is blocked and dropped

- **Scenario:** A legitimate BAP is behind private addressing, or a transient DNS failure occurs.
- **Why it fails:** The SSRF validator rejects loopback/link-local/site-local addresses and treats a DNS resolution failure as a non-retryable reject → dead-letter (`HttpService`).
- **Impact:** The callback is dropped with no DNS retry; the buyer gets nothing.
- **Severity:** Medium

## 5.6 DNS rebinding defeats the SSRF check

- **Scenario:** The SSRF check passes at validation time, then DNS rebinds before the POST connects.
- **Why it fails:** The host is resolved once for validation and re-resolved by the HTTP client at connect time; there is no IP-pinned request factory (documented TODO) (`HttpService`).
- **Impact:** The POST could reach an unintended internal target after passing the public-IP check. (Security; attacker-controlled DNS only.)
- **Severity:** Medium

## 5.7 Callbacks are sent unsigned and cannot be verified

- **Scenario:** Signing is left at its default-disabled setting.
- **Why it fails:** The Beckn HTTP-signature header is only attached when signing is enabled, and the default is off (`HttpService`).
- **Impact:** The BAP receives an `on_discover` it cannot authenticate — it either rejects it (no delivery) or accepts it unverified. (Security.)
- **Severity:** Medium

## 5.8 A transiently-corrupt envelope is dropped on first sight

- **Scenario:** A Kafka envelope fails to parse.
- **Why it fails:** An unparseable envelope short-circuits straight to the dead-letter topic and acks, with no processing retry (`EventListener`).
- **Impact:** Fine for truly-bad data, but lossy if the corruption was transient.
- **Severity:** Medium

## 5.9 A 409 from a temporarily-not-ready BAP loses the callback

- **Scenario:** A BAP returns HTTP 409 ("ack, don't call back") while it is temporarily not ready.
- **Why it fails:** A 409 is counted as a successful delivery — no retry, no re-queue (`HttpService`).
- **Impact:** Correct per spec, but a BAP that returns 409 while temporarily unready loses that callback permanently.
- **Severity:** Low

# The silent-failure shortlist

If you only harden a handful of paths, make it these — every one returns "success" (or an ACK) to the caller while losing, narrowing, or corrupting data:

- **ACK before durable accept (Intake)** — buyer told "ACK", request never queued on a Kafka outage, retry suppressed by the dedup cache.
- **Commit-then-async-index gap (Ingest)** — resource committed to the database but never indexed; no replay re-drives it.
- **FULL replace divergence (Ingest)** — stale/duplicate ES docs survive, or the database is wiped while the index keeps orphans.
- **Embedding-skipped resources (Ingest)** — indexed without a vector, invisible to semantic search forever.
- **Over-pruning to empty (Response)** — strict schema match or broken offer↔resource refs empty a catalog; the buyer can't tell it from a true no-match.
- **Missing index reads as empty (Query)** — a wrong alias silently zeroes every text search.
- **Registry mis-delivery (Delivery)** — one BAP's results delivered to another, or dead-lettered; the buyer already has an ACK.
- **Transient → terminal dead-letter (Delivery)** — a temporarily-down BAP loses its results once the bounded retries exhaust.
- **Duplicate callbacks (Intake & Delivery)** — no end-to-end idempotency key, so retries deliver the same `on_discover` more than once.

# A common root pattern

Across stages the same design choices recur and account for most silent failures:

- **Fire-and-forget Kafka with ACK-before-confirm** — success is reported before durability is guaranteed (intake publish, response publish, dead-letter writes).
- **Commit-then-asynchronously-project** — the database commits, then the index is updated on a best-effort listener with no reconciliation, so the two stores drift silently.
- **Empty as a catch-all** — a missing index, an over-pruned catalog, and a genuine no-match all return the same empty result, so "broken" is indistinguishable from "nothing found".
- **Trust-the-registry, no identity validation** — the callback URL is used without verifying it belongs to the requesting BAP.

Hardening these patterns — confirm-before-ACK, reconcile-the-index, distinguish empty-from-broken, and verify-the-target — would eliminate the majority of the Critical and High scenarios above.

# Severity summary

| Severity | Count |
|---|---|
| Critical | 3 |
| High | 11 |
| Medium | 13 |
| Low | 3 |
| **Total** | **30** |

Critical: 1.1 catalog never searchable · 1.2 FULL-replace duplicate/orphan docs · 2.1 ACK-then-no-callback. (5.1 registry mis-delivery is High/Critical — treat as Critical for the data-leak angle.)

---

*Functional view only. For the infrastructure/observability/alerting complement, see `reliability/RELIABILITY_REPORT.md`. For the engineering-detail companion with full `file:line` citations and verification notes, see `docs/architecture/FUNCTIONAL_FAILURE_ANALYSIS.md`.*

---

## Removed after verification (no longer real, or not in this deployment)

These appeared in an earlier draft but the current code contradicts them — kept here so they aren't re-raised:

- **Text search silently dropped on fallback** — the text-drop path only runs when `discovery.spatial.engine=postgresql`; this deployment always runs `=elasticsearch`, so it is unreachable.
- **Empty embedding → silent zero results** — the embedding client now throws (→ `503`), it does not return an empty result.
- **Query enrichment failure → silent semantic drift** — the enricher throws (→ `503`) on any real failure; only a blank-but-successful LLM response falls back to the raw query, which is benign.
- **Case-7 geo silently dropped** — geo is enforced in the Elasticsearch step; the PostgreSQL re-apply is belt-and-suspenders, so geo intent is never lost.
- **NLWeb double schema-filtering** — NLWeb applies the schema filter exactly once; there is no second filter.
- **MERGE leaves stale locations when a resource shrinks** — fixed: each published resource's location rows are now deleted and re-derived.
