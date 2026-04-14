# Design: Discovr Offer-Only Catalog Support

**Status:** IMPLEMENTED (Design B)
**Date:** 2026-04-07
**Recommendation:** Design B (Extracted OfferResolutionStep)

> **Schema note (2026-04-13):** This design was written before the `table-changes` schema
> redesign. The `item` table PK is now `(id, catalog_id)` — not `(id, bpp_id)`. References
> in this doc to `bpp_id` in item PK, acceptance criteria, and "What NOT to Do" reflect
> the old schema and are superseded. The implemented `OfferResolutionStep` uses `catalog_id`
> for all item lookups and there are no `bpp_id`/`bpp_uri` columns on `item`. See
> `docs/DATABASE_SCHEMA.md` in beckn-catalg for the authoritative schema.

---

## Objective

Enable Discovr's catalog-publish-job to handle offer-only catalogs where a BPP publishes offers that reference resources owned by other BPPs in the same network. Add minimal-resource filtering (Phase 0) and cross-network offer resolution (Phase 3) via a new `OfferResolutionStep` service.

---

## Problem

When Discovr's `PersistenceStep` receives an offer-only catalog:

1. **Phase 0** iterates `resources[]` — minimal resources (ID + resourceAttributes only, no descriptor) would create garbage `item` rows.
2. **Phase 1** looks up by `findAllByIdInAndBppId(ids, ctx.bppId())` — won't match resources owned by a different BPP.
3. **Phase 2** queries `findAllByBppIdAndAnyOfferId(ctx.bppId(), offerIds)` — also filtered by `bpp_id`, so cross-BPP items are never found.

**Example:** BPP-A (EV charging station) publishes resources. BPP-B (Axis Bank) publishes an offer-only catalog with a discount offer referencing BPP-A's resources via `resourceIds`. Today this silently fails.

---

## Design Proposals

### Design A: Inline Phase 3 in PersistenceStep

Add Phase 3 directly inside `PersistenceStep.persistItemsAndLocations()` after Phase 2.

- **Pros:** No new classes, all logic in one place
- **Cons:** PersistenceStep grows from 301 to ~370 lines. Phase 3 testing requires full Phase 0/1/2 context setup.

### Design B: Extracted OfferResolutionStep (RECOMMENDED)

Extract cross-BPP offer resolution into a dedicated `OfferResolutionStep` service. PersistenceStep calls it after Phase 2.

- **Pros:** Testable in isolation, follows step pattern, single responsibility
- **Cons:** One more class, slightly more indirection

### Scoring

| Criterion | Weight | A (Inline) | B (Extracted) |
|-----------|--------|------------|---------------|
| Correctness | 20% | 5 | 5 |
| Performance | 20% | 5 | 5 |
| Maintainability | 15% | 3 | 5 |
| Testability | 10% | 3 | 5 |
| Simplicity | 5% | 4 | 3 |
| **Weighted total** | | **4.45** | **4.90** |

---

## Design Spec (Design B)

### Flow

```
Catalog arrives at PersistenceStep
    │
    Phase 0: Filter resources
    │   ├── Has descriptor? → Real resource → persist normally (existing behavior)
    │   └── No descriptor? → Minimal reference → skip
    │
    Phase 1: Upsert explicit items (unchanged)
    Phase 2: Same-BPP offer propagation (unchanged)
    │
    Phase 3: Cross-BPP offer resolution (NEW)
    │   ├── Collect all resourceIds from offers
    │   ├── Remove IDs already handled in Phase 1/2
    │   ├── Query DB: items by ID + network overlap (no BPP filter)
    │   ├── For each found item: merge offers into payload (RFC 7396)
    │   ├── Update offer_ids array
    │   └── Add to built list → saveAll → ES re-index
    │
    saveAll() → CatalogPersistedEvent → ElasticIndexStep
```

### Files to Create

| File | Purpose |
|------|---------|
| `step/OfferResolutionStep.java` | Cross-BPP offer resolution service. Queries DB for target items by resourceId + network overlap. Merges offers via PayloadMergeService. |
| `db/migration/V10__Add_item_network_id_gin_index.sql` | GIN index on `item.network_id` for efficient array-overlap queries |
| `test/.../step/OfferResolutionStepTest.java` | Unit tests with mocked ItemStore + PayloadMergeService |
| `test/.../util/FieldExtractorIsRealResourceTest.java` | Unit tests for `isRealResource()` |
| `test/.../integration/OfferOnlyPublishIntegrationTest.java` | E2E integration tests |

### Files to Modify

| File | Change |
|------|--------|
| `step/PersistenceStep.java` | Add `OfferResolutionStep` as constructor param. Phase 0: add `isRealResource()` check. After Phase 2: call `offerResolutionStep.resolveCrossBppOffers()`, add results to `built` list. |
| `util/FieldExtractor.java` | Add `isRealResource(JsonNode)`: returns true if resource has non-null `descriptor` field |
| `store/ItemStore.java` | Add `findAllByIdIn(List<String> ids)` — cross-network lookup by resource ID only |
| `store/jpa/ItemJpaRepository.java` | Add Spring Data method: `List<Item> findAllByIdIn(List<String> ids)` |
| `store/jpa/JpaItemStore.java` | Implement with empty-list guard and 500-row chunking to avoid PostgreSQL bind parameter limits |
| `logging/LogEvent.java` | Add `OFFER_RESOLVE_COMPLETED`, `OFFER_RESOLVE_SKIPPED` |
| `metrics/CatalogPublishMetrics.java` | Add counters: `catalog.offer.resolve.success`, `catalog.offer.resolve.missing` |

### Key Interfaces

```java
// ── OfferResolutionStep ─────────────────────────────────────────────
@Service
public class OfferResolutionStep {

    public record ResolvedItem(Item item, JsonNode payloadNode) {}

    /**
     * Resolves cross-BPP offer attachment.
     * Finds items by resourceIds within the same network, merges offers.
     */
    public List<ResolvedItem> resolveCrossBppOffers(
            Map<String, JsonNode> incomingOfferById,
            Set<String> alreadyHandledIds,
            CatalogContext ctx);
}

// ── FieldExtractor addition ─────────────────────────────────────────
/** Returns true if the resource has a descriptor (is a real published resource). */
public static boolean isRealResource(JsonNode resourceNode);

// ── ItemStore addition ──────────────────────────────────────────────
// Cross-network by design: offer resourceIds are globally unique across BPPs.
// Network isolation is enforced at the evaluator/delivery layer, not at index time.
List<Item> findAllByIdIn(List<String> itemIds);
```

### DB Migration

No new migration required for Phase 3. The `findAllByIdIn` query uses the existing PK index on `item.id`.
The previously proposed `V10__Add_item_network_id_gin_index.sql` (GIN index on `network_id`) is not needed
because Phase 3 does not filter by network.

---

## Acceptance Criteria

- [ ] Resources without `descriptor` are skipped — no garbage item rows
- [ ] Resources WITH `descriptor` are persisted normally (existing behavior)
- [ ] Cross-BPP offer: offer referencing another BPP's resource is merged into that resource's item payload
- [ ] Updated item retains original BPP's `bpp_id`/`bpp_uri` (not the offer publisher's)
- [ ] `offer_ids` DB column updated with new offer ID
- [ ] Updated items are ES re-indexed via existing `CatalogPersistedEvent`
- [ ] Offer update: same offer ID re-published → merged (RFC 7396), not duplicated
- [ ] Missing resourceId: WARN log + `OFFER_RESOLVE_SKIPPED` + metric counter, not a failure
- [ ] Items already handled in Phase 1/2 are NOT re-processed in Phase 3
- [ ] Network isolation: resources in different network are NOT found
- [ ] Mixed catalog: real resources persisted, minimal references skipped, offers resolved
- [ ] All existing `PatchFlowIntegrationTest` tests pass unchanged
- [ ] No `Thread.sleep()` in tests
- [ ] Constructor injection only
- [ ] Parameterized SQL only

---

## What NOT to Do

- **Do NOT modify Phase 1 or Phase 2** — they work for same-BPP
- **Do NOT change the item table PK** — composite `(id, bpp_id)` is correct
- **Do NOT set the publishing BPP's `bpp_id` on cross-BPP items** — item retains its original owner
- **Do NOT create new Kafka topics** — existing topics suffice
- **Do NOT fail the entire publish for missing resourceIds** — log + skip + continue
- **Do NOT add a network filter to Phase 3 resource lookup** — `findAllByIdIn` is intentionally cross-network; resource IDs are globally unique and network isolation is enforced at the evaluator/delivery layer
- **Do NOT use `@Autowired` field injection** — constructor injection only
- **Do NOT use `new ObjectMapper()`** — inject Spring Boot's bean
- **Do NOT break existing tests**

---

## Integration Test Scenarios

1. **Happy path (cross-BPP):** BPP-A publishes resource → BPP-B publishes offer-only referencing BPP-A's resource → verify offer attached to BPP-A's item in DB + ES
2. **Offer update:** BPP-B publishes offer with discount=10% → publishes again with discount=20% → verify merge (not duplicate)
3. **Missing resourceId:** BPP-B references non-existent resource → verify warning log + metric, no failure
4. **Mixed catalog:** Catalog with real resources + minimal references + offers → real resources persisted, minimal skipped, offers resolved
5. **Network isolation:** BPP-B in network-X references resource in network-Y → verify resource NOT found
