# Design: Provider-Level Offers

**Status:** APPROVED
**Date:** 2026-04-19
**Requirement:** `docs/design/offer_providers/requirement.md`

---

## Problem

An offer published WITHOUT `resourceIds` but WITH a `provider` object is a "provider-level offer" — applicable to ALL resources from that provider.

Today, Discovr's `OfferIndex` treats offers without `resourceIds` as `catalogWideOffers` and stamps them into every item's JSONB payload. For a provider with 1000 items, one provider-level offer creates 1000 copies — massive write amplification.

At search time, `CatalogProcessor.filterOffersByItemIds` removes offers without `resourceIds`, so they never appear in results.

## Solution

New `provider_offer` table in Discovr. Provider-level offers are stored once, resolved at search time.

### Classification (2-way)

- **Has `resourceIds`** → item-level offer. Stamped on specific items (existing behavior, unchanged).
- **No `resourceIds`** → provider-level offer. Stored in `provider_offer` table. Not stamped on items.

The old `catalogWideOffers` bucket is eliminated. Since `provider` is **required** on Catalog (per beckn.yaml schema), every offer without `resourceIds` is implicitly a provider-level offer — `provider_id` is always `catalog.provider.id`.

### New Table

```sql
CREATE TABLE IF NOT EXISTS provider_offer (
    offer_id        TEXT          NOT NULL,
    catalog_id      TEXT          NOT NULL,
    provider_id     TEXT          NOT NULL,
    payload         JSONB         NOT NULL,
    created_by      VARCHAR(255),
    updated_by      VARCHAR(255),
    subscriber_id   VARCHAR(255),
    created_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (offer_id, catalog_id)
);
CREATE INDEX idx_provider_offer_provider ON provider_offer(provider_id);
CREATE INDEX idx_provider_offer_catalog ON provider_offer(catalog_id);
```

**PK:** `(offer_id, catalog_id)` — same offer ID can appear in different catalogs.
**`provider_id` index:** enricher lookup `WHERE provider_id IN (...)`.
**`catalog_id` index:** FULL mode `DELETE WHERE catalog_id = ?`.
**No `network_id`:** provider offers apply to all resources of the provider; items are already network-scoped.

### Write Path (Discovr catalog-publish-job)

**`OfferIndex.java`** — 2-way classification:
- `offersByItemId` — offers with `resourceIds` (keyed by resource ID)
- `providerOffers` — offers without `resourceIds` (list)
- `catalogWideOffers` bucket removed

**`PersistenceStep.java`** — new Phase 4 after existing Phase 3:

```
Phase 1: Process items with resource-scoped offers (existing)
Phase 2: Offer propagation to same-catalog items (existing)
Phase 3: Cross-BPP offer resolution (existing)
Phase 4: Persist provider-level offers to provider_offer table (NEW)
         - FULL mode: DELETE FROM provider_offer WHERE catalog_id = ?
         - MERGE mode: upsert by (offer_id, catalog_id)
         - provider_id = catalog.provider.id (always from catalog, never from offer)

// Phase 4 runs BEFORE the built.isEmpty() early return
// so offer-only catalogs (no resources) still persist provider offers
persistProviderOffers(offerIndex, catalogId, ctx, isFullReplace);

if (built.isEmpty()) {
    return new CatalogBatch(...);
}
```

**MERGE semantics:** upsert incoming offers, preserve existing offers not in incoming set. If BPP wants to remove a provider-level offer, they use FULL publish.

**FULL semantics:** delete all provider offers for that `catalog_id`, then insert new ones.

### Read Path (Discovr catalog-discover-job)

Provider offers are a **post-processing enrichment** applied AFTER the CatalogPipeline, to results from ANY search engine (PostgreSQL, ES, NLWeb).

```
Search results (from any engine)
    → PostgreSQLAssembler / ElasticsearchAssembler / NLWebAssembler
    → CatalogPipeline (5 steps — provider offers NOT in pipeline, no changes needed)
    → ProviderOfferEnricher (NEW — runs after pipeline)
        1. Collect unique providerIds from all catalogs in results
        2. Query: SELECT offer_id, provider_id, payload FROM provider_offer
                  WHERE provider_id IN (:providerIds)
        3. Group by provider_id
        4. For each catalog: append matching provider offers to catalog.offers
    → Return enriched results
```

**No CatalogPipeline changes needed.** Provider offers are added after the pipeline runs, so `filterOffersByItemIds` (Step 4) and `filterItemsByOfferReferences` (Step 3) never see them.

**No ES changes needed.** Provider offers are resolved from PostgreSQL regardless of which search engine produced the results.

### Example Flow

1. Provider ABC publishes **Catalog-1**: R1, R2, R3 + O1(→R1), O2(→R2), O3(→R3)
   - O1, O2, O3 have `resourceIds` → stamped on items (existing behavior)

2. Provider ABC publishes **Catalog-2** (offer-only): O4 (no `resourceIds`)
   - O4 has no `resourceIds` → stored in `provider_offer` table:
     `(offer_id=O4, catalog_id=Catalog-2, provider_id=ABC, payload={...})`
   - No items to process → `built.isEmpty()` returns early AFTER Phase 4

3. Search returns R1 (from Catalog-1, provider ABC)
   - Pipeline processes Catalog-1 with O1 on R1 (existing)
   - ProviderOfferEnricher: `SELECT ... FROM provider_offer WHERE provider_id IN ('ABC')`
   - O4 found → appended to Catalog-1's offers list
   - Response: Catalog-1 has R1 with offers [O1, O4]

---

## Scope

### Changes in Discovr (beckn-discovr) ONLY — zero Catalg changes

**New files:**

| File | Purpose |
|------|---------|
| `catalog-publish-job/.../model/ProviderOffer.java` | Entity: offer_id, catalog_id, provider_id, payload, ownership, timestamps |
| `catalog-publish-job/.../model/ProviderOfferId.java` | Composite PK class |
| `catalog-publish-job/.../store/ProviderOfferStore.java` | Interface: saveAll, deleteByCatalogId |
| `catalog-publish-job/.../store/JpaProviderOfferStore.java` | JPA/JDBC implementation |
| `catalog-publish-job/src/main/resources/db/migration/V3__create_provider_offer_table.sql` | Flyway migration |
| `catalog-discover-job/.../postgresql/ProviderOfferRepository.java` | Read-only: findByProviderIds |
| `catalog-discover-job/.../postgresql/ProviderOfferEnricher.java` | Post-pipeline enrichment |

**Modified files:**

| File | Change |
|------|--------|
| `catalog-publish-job/.../dto/OfferIndex.java` | 2-way classification: remove `catalogWideOffers`, add `providerOffers` |
| `catalog-publish-job/.../step/PersistenceStep.java` | Phase 4: persist provider offers before `built.isEmpty()` guard |
| `catalog-discover-job/.../DiscoveryService.java` or `PostgreSQLQueryEngine.java` | Call `ProviderOfferEnricher.enrich()` after pipeline |

**NOT modified:**

| File | Reason |
|------|--------|
| `CatalogPipeline.java` | Provider offers added after pipeline — no changes needed |
| `CatalogProcessor.java` | Provider offers never enter pipeline — no filtering issues |
| `CatalogDocumentAssembler.java` | No ES changes — enrichment is post-search |
| Any Catalg (beckn-catalg) file | Catalg stores and distributes as-is |

---

## Acceptance Criteria

- [ ] Offers without `resourceIds` are stored in `provider_offer` table, NOT stamped into item payload
- [ ] `provider_id` always comes from `catalog.provider.id`
- [ ] FULL: deletes all provider offers for catalog_id, inserts new ones
- [ ] MERGE: upserts by (offer_id, catalog_id), preserves existing
- [ ] Offer-only catalogs (no resources) persist provider offers correctly
- [ ] Search results from ANY engine are enriched with provider offers
- [ ] Offers WITH `resourceIds` continue to work as before (no regression)
- [ ] `updated_at` is set explicitly on upsert
- [ ] No write amplification: provider-level offer stored exactly once

## What NOT to Do

- Do NOT add `provider_id` column to `item` table
- Do NOT add `network_id` to `provider_offer` table
- Do NOT store provider offers in Elasticsearch
- Do NOT modify CatalogPipeline or CatalogProcessor
- Do NOT modify any Catalg (beckn-catalg) code
- Do NOT add provider offers to `item.offer_ids` array
- Do NOT use 3-way classification — only 2-way (has resourceIds vs doesn't)
