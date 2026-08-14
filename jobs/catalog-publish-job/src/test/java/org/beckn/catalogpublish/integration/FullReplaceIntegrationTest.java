package org.beckn.catalogpublish.integration;

import org.beckn.catalogpublish.model.Item;
import org.beckn.catalogpublish.model.ItemId;
import org.beckn.catalogpublish.orchestration.CatalogPublishOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FULL replace mode (updateMode=FULL in publishDirectives).
 *
 * <p>FULL mode deletes all existing items for the catalog before inserting fresh ones.
 * Items from the previous publish that are not in the new payload are removed.
 */
class FullReplaceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    CatalogPublishOrchestrator orchestrator;

    /**
     * Happy path: FULL replace deletes old items and inserts only the new ones.
     * item-old exists after round-1; round-2 sends updateMode=FULL with only item-new.
     * After round-2, only item-new must exist — item-old must be gone.
     */
    @Test
    void fullReplace_deletesStaleItemsAndInsertsNewOnes() {
        // Round 1: MERGE publish — item-old and item-keep
        String round1 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m1","transactionId":"t1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [
                      {"id": "item-old",  "descriptor": {"name": "Old Item"}},
                      {"id": "item-keep", "descriptor": {"name": "Keep Item"}}
                    ],
                    "offers": []}]}
                }""";
        orchestrator.processPublish(round1);
        assertThat(itemRepository.count()).isEqualTo(2);
        assertThat(itemRepository.findById(new ItemId("item-old",  "cat-1"))).isPresent();
        assertThat(itemRepository.findById(new ItemId("item-keep", "cat-1"))).isPresent();

        // Round 2: FULL replace — only item-new in the payload
        String round2 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {
                    "publishDirectives": [{"catalogId":"cat-1","catalogType":"regular","updateMode":"FULL"}],
                    "catalogs": [{
                      "id": "cat-1",
                      "resources": [
                        {"id": "item-new", "descriptor": {"name": "New Item"}}
                      ],
                      "offers": []
                    }]
                  }
                }""";
        var results = orchestrator.processPublish(round2).results();
        assertThat(results).hasSize(1);

        // item-old and item-keep must be gone; only item-new must exist
        assertThat(itemRepository.count()).isEqualTo(1);
        assertThat(itemRepository.findById(new ItemId("item-old",  "cat-1"))).isEmpty();
        assertThat(itemRepository.findById(new ItemId("item-keep", "cat-1"))).isEmpty();

        var newItem = itemRepository.findById(new ItemId("item-new", "cat-1")).orElseThrow();
        assertThat(newItem.getPayload()).contains("New Item");
        assertThat(newItem.getCatalogId()).isEqualTo("cat-1");
    }

    /**
     * FULL replace is catalog-scoped: items from a different catalog belonging to the
     * same BPP must NOT be deleted.
     */
    @Test
    void fullReplace_onlyDeletesItemsOfTargetCatalog() {
        // Round 1: two catalogs — cat-1 and cat-2
        String round1 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m1","transactionId":"t1"},
                  "message": {"catalogs": [
                    {"id": "cat-1",
                      "resources": [{"id": "cat1-item", "descriptor": {"name": "Cat1 Item"}}],
                      "offers": []},
                    {"id": "cat-2",
                      "resources": [{"id": "cat2-item", "descriptor": {"name": "Cat2 Item"}}],
                      "offers": []}
                  ]}
                }""";
        orchestrator.processPublish(round1);
        assertThat(itemRepository.count()).isEqualTo(2);

        // Round 2: FULL replace on cat-1 only — cat-2 item must survive
        String round2 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {
                    "publishDirectives": [{"catalogId":"cat-1","catalogType":"regular","updateMode":"FULL"}],
                    "catalogs": [{
                      "id": "cat-1",
                      "resources": [
                        {"id": "cat1-item-new", "descriptor": {"name": "Cat1 New Item"}}
                      ],
                      "offers": []
                    }]
                  }
                }""";
        orchestrator.processPublish(round2);

        // cat-1 items replaced; cat-2 item unaffected
        assertThat(itemRepository.count()).isEqualTo(2);
        assertThat(itemRepository.findById(new ItemId("cat1-item",     "cat-1"))).isEmpty();
        assertThat(itemRepository.findById(new ItemId("cat1-item-new", "cat-1"))).isPresent();
        assertThat(itemRepository.findById(new ItemId("cat2-item",     "cat-2"))).isPresent();
    }

    /**
     * Idempotency: two consecutive FULL replaces with the same payload produce exactly one row.
     */
    @Test
    void fullReplace_idempotent_twoIdenticalReplaces() {
        String payload = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m1","transactionId":"t1"},
                  "message": {
                    "publishDirectives": [{"catalogId":"cat-1","catalogType":"regular","updateMode":"FULL"}],
                    "catalogs": [{
                      "id": "cat-1",
                      "resources": [
                        {"id": "item-a", "descriptor": {"name": "Item A"}}
                      ],
                      "offers": []
                    }]
                  }
                }""";
        orchestrator.processPublish(payload);
        orchestrator.processPublish(payload);

        assertThat(itemRepository.count()).isEqualTo(1);
        var item = itemRepository.findById(new ItemId("item-a", "cat-1")).orElseThrow();
        assertThat(item.getPayload()).contains("Item A");
    }

    /**
     * Default mode (no publishDirectives) is MERGE — items from previous publishes are preserved
     * unless explicitly included in the new payload.
     */
    @Test
    void mergeMode_default_preservesExistingItems() {
        // Round 1: two items
        String round1 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m1","transactionId":"t1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [
                      {"id": "item-1", "descriptor": {"name": "Item One"}},
                      {"id": "item-2", "descriptor": {"name": "Item Two"}}
                    ],
                    "offers": []}]}
                }""";
        orchestrator.processPublish(round1);
        assertThat(itemRepository.count()).isEqualTo(2);

        // Round 2: MERGE (default) — only item-1 in payload; item-2 must survive
        String round2 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [
                      {"id": "item-1", "descriptor": {"name": "Item One Updated"}}
                    ],
                    "offers": []}]}
                }""";
        orchestrator.processPublish(round2);

        assertThat(itemRepository.count()).isEqualTo(2);
        var item1 = itemRepository.findById(new ItemId("item-1", "cat-1")).orElseThrow();
        var item2 = itemRepository.findById(new ItemId("item-2", "cat-1")).orElseThrow();
        assertThat(item1.getPayload()).contains("Item One Updated");
        assertThat(item2.getPayload()).contains("Item Two");
    }

    /**
     * Baseline for the offer-preservation tests: cat-1 holds res-1 and res-2, both
     * linked to offer-1.
     */
    private static final String OFFER_BASELINE = """
            {
              "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                           "messageId":"m1","transactionId":"t1"},
              "message": {"catalogs": [{"id": "cat-1",
                "resources": [
                  {"id": "res-1", "descriptor": {"name": "Resource One"}},
                  {"id": "res-2", "descriptor": {"name": "Resource Two"}}
                ],
                "offers": [
                  {"id": "offer-1", "descriptor": {"name": "Ten Percent Off"},
                   "resourceIds": ["res-1", "res-2"]}
                ]}]}
            }""";

    /**
     * MERGE mode: a push that updates a resource and says nothing at all about offers
     * must NOT drop the offers already linked to that resource.
     *
     * <p>Regression test for the offer-wipe bug: {@code buildDenormalizedPayloadFromSlice}
     * rebuilds {@code catalogs[0].offers} purely from the incoming payload, so an absent
     * {@code offers} array silently cleared every stored offer — and since discover hides
     * resources with no offers, a routine price update delisted the resource.
     *
     * <p>Silence means "not discussing offers", not "delete all offers".
     */
    @Test
    void mergeMode_pushWithNoOffersSection_preservesExistingOffers() {
        orchestrator.processPublish(OFFER_BASELINE);
        assertThat(itemRepository.findById(new ItemId("res-2", "cat-1")).orElseThrow()
                .getOfferIds()).containsExactly("offer-1");

        // Round 2: MERGE — only res-2, and no "offers" key anywhere in the message
        String round2 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [
                      {"id": "res-2", "descriptor": {"name": "Resource Two Updated"}}
                    ]}]}
                }""";
        orchestrator.processPublish(round2);

        var res2 = itemRepository.findById(new ItemId("res-2", "cat-1")).orElseThrow();
        assertThat(res2.getPayload()).contains("Resource Two Updated");
        assertThat(res2.getOfferIds())
                .as("offer must survive a resource-only push")
                .containsExactly("offer-1");
        assertThat(res2.getPayload())
                .as("offer body must survive, not just the offer_ids column")
                .contains("Ten Percent Off");

        // res-1 was not in the payload at all — untouched
        assertThat(itemRepository.findById(new ItemId("res-1", "cat-1")).orElseThrow()
                .getOfferIds()).containsExactly("offer-1");
    }

    /**
     * MERGE mode: restating an offer with narrower {@code resourceIds} must detach it from
     * the resources it no longer lists.
     *
     * <p>This is the counterpart to {@link #mergeMode_pushWithNoOffersSection_preservesExistingOffers}
     * and guards the pre-existing behaviour: mentioning an offer declares its complete
     * current resource list, so omission from that list is a deliberate unlink. Preserving
     * offers in this case too would make offers impossible to detach in MERGE mode.
     */
    @Test
    void mergeMode_offerRestatedWithoutResource_detachesIt() {
        orchestrator.processPublish(OFFER_BASELINE);

        // Round 2: offer-1 restated, but now listing res-2 only
        String round2 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [
                      {"id": "res-1", "descriptor": {"name": "Resource One"}},
                      {"id": "res-2", "descriptor": {"name": "Resource Two"}}
                    ],
                    "offers": [
                      {"id": "offer-1", "descriptor": {"name": "Ten Percent Off"},
                       "resourceIds": ["res-2"]}
                    ]}]}
                }""";
        orchestrator.processPublish(round2);

        assertThat(itemRepository.findById(new ItemId("res-1", "cat-1")).orElseThrow().getOfferIds())
                .as("restating an offer without res-1 must unlink it")
                .isEmpty();
        assertThat(itemRepository.findById(new ItemId("res-2", "cat-1")).orElseThrow().getOfferIds())
                .containsExactly("offer-1");
    }

    /**
     * MERGE mode: republishing the same catalog unchanged is idempotent for offers —
     * no duplicate entries in {@code offers[]} or {@code offer_ids}.
     */
    @Test
    void mergeMode_offerRestatedIdentically_isIdempotent() {
        orchestrator.processPublish(OFFER_BASELINE);
        orchestrator.processPublish(OFFER_BASELINE);

        assertThat(itemRepository.count()).isEqualTo(2);
        assertThat(itemRepository.findById(new ItemId("res-1", "cat-1")).orElseThrow().getOfferIds())
                .containsExactly("offer-1");
        assertThat(itemRepository.findById(new ItemId("res-2", "cat-1")).orElseThrow().getOfferIds())
                .containsExactly("offer-1");
    }

    /**
     * FULL replace with no offers clears them — offer preservation is a MERGE-only concern.
     * FULL means "this payload is the whole truth for the catalog".
     */
    @Test
    void fullReplace_withNoOffersSection_clearsOffers() {
        orchestrator.processPublish(OFFER_BASELINE);

        String round2 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {
                    "publishDirectives": [{"catalogId":"cat-1","catalogType":"regular","updateMode":"FULL"}],
                    "catalogs": [{"id": "cat-1",
                      "resources": [
                        {"id": "res-2", "descriptor": {"name": "Resource Two Only"}}
                      ]}]}
                }""";
        orchestrator.processPublish(round2);

        assertThat(itemRepository.count()).isEqualTo(1);
        assertThat(itemRepository.findById(new ItemId("res-2", "cat-1")).orElseThrow().getOfferIds())
                .as("FULL replace is authoritative — offers not in the payload are dropped")
                .isEmpty();
    }

    /**
     * Location isolation: FULL replace on catalog-A must not delete locations of items
     * with the same id in catalog-B.
     *
     * Regression test for the unsafe subquery DELETE that matched item ids across catalogs.
     * The fix uses a JOIN: DELETE ... USING item WHERE ilc.item_id = i.id AND i.catalog_id = ?
     */
    @Test
    void fullReplace_locationDeleteScopedToCatalog_otherCatalogLocationsUnaffected() {
        // Round 1: publish res-001 in catalog-A and catalog-B with locations in each
        String publishA = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m1","transactionId":"t1"},
                  "message": {"catalogs": [{
                    "id": "cat-loc-A",
                    "resources": [{
                      "id": "res-shared",
                      "descriptor": {"name": "Shared Resource A"},
                      "availableAt": [{"geo": {"type": "Point", "coordinates": [77.5, 12.9]}}]
                    }],
                    "offers": []
                  }]}
                }""";
        String publishB = """
                {
                  "context": {"bppId":"bpp-2","bppUri":"http://bpp2.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {"catalogs": [{
                    "id": "cat-loc-B",
                    "resources": [{
                      "id": "res-shared",
                      "descriptor": {"name": "Shared Resource B"},
                      "availableAt": [{"geo": {"type": "Point", "coordinates": [78.0, 13.0]}}]
                    }],
                    "offers": []
                  }]}
                }""";
        orchestrator.processPublish(publishA);
        orchestrator.processPublish(publishB);

        // Both catalogs must have locations before the FULL replace
        long locationsBeforeA = locationRepository.findAll().stream()
                .filter(l -> "cat-loc-A".equals(l.getId().getCatalogId()))
                .count();
        long locationsBeforeB = locationRepository.findAll().stream()
                .filter(l -> "cat-loc-B".equals(l.getId().getCatalogId()))
                .count();
        assertThat(locationsBeforeA).as("cat-loc-A must have locations").isGreaterThan(0);
        assertThat(locationsBeforeB).as("cat-loc-B must have locations").isGreaterThan(0);

        // Round 2: FULL replace on cat-loc-A — should NOT touch cat-loc-B
        String fullReplaceA = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m3","transactionId":"t3"},
                  "message": {
                    "publishDirectives": [{"catalogId":"cat-loc-A","catalogType":"regular","updateMode":"FULL"}],
                    "catalogs": [{
                      "id": "cat-loc-A",
                      "resources": [{
                        "id": "res-new",
                        "descriptor": {"name": "New Resource A"}
                      }],
                      "offers": []
                    }]
                  }
                }""";
        orchestrator.processPublish(fullReplaceA);

        // cat-loc-A: old item gone, new item present
        assertThat(itemRepository.findById(new ItemId("res-shared", "cat-loc-A"))).isEmpty();
        assertThat(itemRepository.findById(new ItemId("res-new",    "cat-loc-A"))).isPresent();

        // cat-loc-A: item_location_collection rows for deleted item must also be gone
        long locationsAfterAFull = locationRepository.findAll().stream()
                .filter(l -> "cat-loc-A".equals(l.getId().getCatalogId()))
                .count();
        assertThat(locationsAfterAFull)
                .as("item_location_collection rows for cat-loc-A must be deleted by FULL replace")
                .isEqualTo(0);

        // cat-loc-B: res-shared must still have a location
        assertThat(itemRepository.findById(new ItemId("res-shared", "cat-loc-B"))).isPresent();
        long locationsAfterBFull = locationRepository.findAll().stream()
                .filter(l -> "cat-loc-B".equals(l.getId().getCatalogId()))
                .count();
        assertThat(locationsAfterBFull)
                .as("cat-loc-B locations must survive FULL replace of cat-loc-A")
                .isGreaterThan(0);
    }
}
