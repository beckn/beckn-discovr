package org.beckn.catalogpublish.integration;

import org.beckn.catalogpublish.model.ItemId;
import org.beckn.catalogpublish.orchestration.CatalogPublishOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class PatchFlowIntegrationTest extends BaseIntegrationTest {

    @Autowired
    CatalogPublishOrchestrator orchestrator;

    @Test
    void upsertPublish_mergesExistingItem() {
        String publishFixture = readFixture("fixtures/ev_charging_station_data.json");
        orchestrator.processPublish(publishFixture);
        assertThat(itemRepository.count()).isEqualTo(1);

        var itemAfterPublish = itemRepository.findAll().get(0);
        assertThat(itemAfterPublish.getId()).isEqualTo("item-1");
        assertThat(itemAfterPublish.getBppId()).isEqualTo("bpp-1");
        assertThat(itemAfterPublish.getName()).isEqualTo("EV Station");
        assertThat(itemAfterPublish.getCatalogId()).isEqualTo("cat-1");

        String patchFixture = readFixture("fixtures/ev_charging_patch_update.json");
        var results = orchestrator.processPublish(patchFixture).results();
        assertThat(results).hasSize(1);
        assertThat(itemRepository.count()).isEqualTo(1);
        var item = itemRepository.findAll().get(0);
        assertThat(item.getPayload()).contains("EV Station Updated");
        assertThat(item.getName()).isEqualTo("EV Station Updated");
        assertThat(item.getCatalogId()).isEqualTo("cat-1");
    }

    /**
     * Null fields in the second publish must NOT delete existing stored data.
     * The item's gps, id, and other fields published in round-1 must survive
     * even when the round-2 publish sends those fields as null.
     */
    @Test
    void upsertPublish_nullFieldsInSecondPublish_doNotDeleteExistingData() {
        // Round 1: publish item with name + gps
        String round1 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m1","transactionId":"t1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [{"id": "item-1",
                      "descriptor": {"name": "EV Station"},
                      "gps": "12.34,56.78"}],
                    "offers": []}]}
                }""";
        orchestrator.processPublish(round1);
        assertThat(itemRepository.count()).isEqualTo(1);
        var afterRound1 = itemRepository.findAll().get(0);
        assertThat(afterRound1.getPayload()).contains("EV Station").contains("12.34,56.78");

        // Round 2: publish same item with name set to null and gps absent — neither should delete stored data
        String round2 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [{"id": "item-1",
                      "descriptor": {"name": null}}],
                    "offers": []}]}
                }""";
        orchestrator.processPublish(round2);

        assertThat(itemRepository.count()).isEqualTo(1);
        var afterRound2 = itemRepository.findAll().get(0);
        // null name must not delete the stored name
        assertThat(afterRound2.getPayload()).contains("EV Station");
        // absent gps must not delete the stored gps
        assertThat(afterRound2.getPayload()).contains("12.34,56.78");
        // item id must remain intact
        assertThat(afterRound2.getId()).isEqualTo("item-1");
    }

    /**
     * Null fields inside an offer in the second publish must NOT delete existing offer data,
     * specifically the resourceIds item-link array that associates the offer to items.
     */
    @Test
    void upsertPublish_nullFieldInOffer_doesNotDeleteOfferItemLink() {
        // Round 1: publish with an offer that links to item-1
        String round1 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m1","transactionId":"t1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [{"id": "item-1",
                      "descriptor": {"name": "EV Station"}}],
                    "offers": [{"id": "offer-1",
                      "resourceIds": ["item-1"],
                      "descriptor": {"name": "Offer One"}}]}]}
                }""";
        orchestrator.processPublish(round1);
        assertThat(itemRepository.count()).isEqualTo(1);
        var afterRound1 = itemRepository.findAll().get(0);
        assertThat(afterRound1.getPayload()).contains("offer-1").contains("Offer One");

        // Round 2: update only the offer name — send null for resourceIds (accidentally omitted/nulled)
        // The resourceIds link inside the stored offer must be preserved
        String round2 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [{"id": "item-1",
                      "descriptor": {"name": "EV Station"}}],
                    "offers": [{"id": "offer-1",
                      "resourceIds": null,
                      "descriptor": {"name": "Offer One Updated"}}]}]}
                }""";
        orchestrator.processPublish(round2);

        assertThat(itemRepository.count()).isEqualTo(1);
        var afterRound2 = itemRepository.findAll().get(0);
        // Offer name must be updated
        assertThat(afterRound2.getPayload()).contains("Offer One Updated");
        // resourceIds link inside the offer must NOT be deleted despite null in round-2
        assertThat(afterRound2.getOfferIds()).contains("offer-1");
        assertThat(afterRound2.getPayload()).contains("\"resourceIds\"");
    }

    /**
     * Offer propagation via DB offer_ids column — explicit item list: when an offer is updated
     * in the incoming payload, every item that references that offer via the offer_ids[] DB
     * column must receive the updated offer data, even if it is NOT listed in the
     * incoming resources array.
     *
     * <p>Round 1 publishes item-1 and item-2 both linked to offer-A (both get
     * offer_ids = ["offer-A"] stored in the DB).
     * Round 2 explicitly lists only item-1 but sends an updated offer-A.
     * Phase 2 must query the DB by offer_ids column and propagate the update to item-2
     * independently of what offer.resourceIds says in the request.
     */
    @Test
    void upsertPublish_offerUpdate_propagatesToUnlistedLinkedItems() {
        // Round 1: establish item-1 and item-2 both linked to offer-A in the DB
        String round1 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m1","transactionId":"t1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [
                      {"id": "item-1", "descriptor": {"name": "Item One"}},
                      {"id": "item-2", "descriptor": {"name": "Item Two"}}
                    ],
                    "offers": [{"id": "offer-A",
                      "resourceIds": ["item-1", "item-2"],
                      "price": "100.00",
                      "descriptor": {"name": "Flash Sale"}}]
                  }]}
                }""";
        orchestrator.processPublish(round1);
        assertThat(itemRepository.count()).isEqualTo(2);
        var item1AfterR1 = itemRepository.findById(new ItemId("item-1", "bpp-1")).orElseThrow();
        var item2AfterR1 = itemRepository.findById(new ItemId("item-2", "bpp-1")).orElseThrow();
        // Verify both items have offer-A in their offer_ids DB column
        assertThat(item1AfterR1.getOfferIds()).contains("offer-A");
        assertThat(item2AfterR1.getOfferIds()).contains("offer-A");
        assertThat(item1AfterR1.getPayload()).contains("100.00");
        assertThat(item2AfterR1.getPayload()).contains("100.00");

        // Round 2: item-1 is explicit; item-2 is NOT in resources.
        // Phase 2 must locate item-2 via the offer_ids DB column and propagate the new price.
        String round2 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [
                      {"id": "item-1", "descriptor": {"name": "Item One"}}
                    ],
                    "offers": [{"id": "offer-A",
                      "resourceIds": ["item-1", "item-2"],
                      "price": "75.00",
                      "descriptor": {"name": "Flash Sale"}}]
                  }]}
                }""";
        orchestrator.processPublish(round2);

        assertThat(itemRepository.count()).isEqualTo(2);
        var item1AfterR2 = itemRepository.findById(new ItemId("item-1", "bpp-1")).orElseThrow();
        var item2AfterR2 = itemRepository.findById(new ItemId("item-2", "bpp-1")).orElseThrow();

        // item-1: updated via Phase 1 (explicit), new price must be present
        assertThat(item1AfterR2.getPayload())
                .as("item-1 (Phase 1 explicit) must have new price")
                .contains("75.00")
                .doesNotContain("100.00");
        assertThat(item1AfterR2.getOfferIds()).contains("offer-A");

        // item-2: NOT in round-2 resources — must be updated via Phase 2 DB column lookup
        assertThat(item2AfterR2.getPayload())
                .as("item-2 (Phase 2 propagation via offer_ids column) must have new price")
                .contains("75.00")
                .doesNotContain("100.00");
        assertThat(item2AfterR2.getOfferIds()).contains("offer-A");
    }

    /**
     * Offer propagation via DB offer_ids column — offer.resourceIds is NOT the source of truth:
     * even if the incoming offer does NOT mention item-2 in its resourceIds array, item-2 must
     * still receive the offer update because it has the offer in its offer_ids[] DB column.
     *
     * <p>This verifies that Phase 2 queries the DB by offer_ids column — it does NOT use
     * offer.resourceIds to decide which items to update.
     */
    @Test
    void upsertPublish_offerUpdate_propagatesViaDbColumn_notOfferBecknItems() {
        // Round 1: both items linked to offer-A
        String round1 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m1","transactionId":"t1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [
                      {"id": "item-1", "descriptor": {"name": "Item One"}},
                      {"id": "item-2", "descriptor": {"name": "Item Two"}}
                    ],
                    "offers": [{"id": "offer-A",
                      "resourceIds": ["item-1", "item-2"],
                      "price": "100.00",
                      "descriptor": {"name": "Flash Sale"}}]
                  }]}
                }""";
        orchestrator.processPublish(round1);
        assertThat(itemRepository.findById(new ItemId("item-1", "bpp-1")).orElseThrow().getOfferIds()).contains("offer-A");
        assertThat(itemRepository.findById(new ItemId("item-2", "bpp-1")).orElseThrow().getOfferIds()).contains("offer-A");

        // Round 2: offer-A updated with resourceIds = ["item-1"] ONLY — item-2 intentionally absent.
        // Despite item-2 being absent from offer.resourceIds, Phase 2 MUST still update item-2
        // because the DB offer_ids column is the source of truth for offer-item linkage.
        String round2 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [],
                    "offers": [{"id": "offer-A",
                      "resourceIds": ["item-1"],
                      "price": "50.00",
                      "descriptor": {"name": "Flash Sale"}}]
                  }]}
                }""";
        orchestrator.processPublish(round2);

        assertThat(itemRepository.count()).isEqualTo(2);
        var item1 = itemRepository.findById(new ItemId("item-1", "bpp-1")).orElseThrow();
        var item2 = itemRepository.findById(new ItemId("item-2", "bpp-1")).orElseThrow();

        // item-1: offer-A mentions it — must be updated via Phase 2 DB lookup
        assertThat(item1.getPayload())
                .as("item-1 must have updated price from offer-A propagation")
                .contains("50.00")
                .doesNotContain("100.00");

        // item-2: offer-A does NOT mention it in round-2 resourceIds, but DB offer_ids has "offer-A"
        // Phase 2 must still propagate the update using the DB column, not offer.resourceIds
        assertThat(item2.getPayload())
                .as("item-2 must be updated via DB offer_ids column even though offer.resourceIds omits it")
                .contains("50.00")
                .doesNotContain("100.00");
        // offer_ids column must still contain offer-A (the link is preserved)
        assertThat(item2.getOfferIds()).contains("offer-A");
    }

    /**
     * Offer-only propagation: when the incoming payload carries offers but NO items,
     * all stored items that link to those offers (via offer_ids[] DB column) must be
     * updated and all updated items must be reflected in the saved batch.
     *
     * <p>Round 1 publishes item-1 and item-2 both linked to offer-A.
     * Round 2 sends an empty resources array with only an updated offer-A.
     * Both items must receive the updated offer data via Phase 2 DB column lookup.
     */
    @Test
    void upsertPublish_offersOnlyPayload_propagatesToAllLinkedItems() {
        // Round 1: both items linked to offer-A stored in DB
        String round1 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m1","transactionId":"t1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [
                      {"id": "item-1", "descriptor": {"name": "Item One"}},
                      {"id": "item-2", "descriptor": {"name": "Item Two"}}
                    ],
                    "offers": [{"id": "offer-A",
                      "resourceIds": ["item-1", "item-2"],
                      "validThrough": "2025-12-31",
                      "descriptor": {"name": "Year-end Offer"}}]
                  }]}
                }""";
        orchestrator.processPublish(round1);
        assertThat(itemRepository.count()).isEqualTo(2);
        assertThat(itemRepository.findById(new ItemId("item-1", "bpp-1")).orElseThrow().getOfferIds()).contains("offer-A");
        assertThat(itemRepository.findById(new ItemId("item-2", "bpp-1")).orElseThrow().getOfferIds()).contains("offer-A");

        // Round 2: no explicit resources at all — only an updated offer.
        // Phase 2 must propagate to BOTH items via the DB offer_ids column.
        String round2 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [],
                    "offers": [{"id": "offer-A",
                      "resourceIds": ["item-1", "item-2"],
                      "validThrough": "2026-06-30",
                      "descriptor": {"name": "Year-end Offer"}}]
                  }]}
                }""";
        var results = orchestrator.processPublish(round2).results();

        // The orchestrator must return a result (not fail silently even with no explicit items)
        assertThat(results).hasSize(1);

        assertThat(itemRepository.count()).isEqualTo(2);
        var item1 = itemRepository.findById(new ItemId("item-1", "bpp-1")).orElseThrow();
        var item2 = itemRepository.findById(new ItemId("item-2", "bpp-1")).orElseThrow();

        // item-1: no explicit items in round-2 → updated ONLY via Phase 2 DB column lookup
        assertThat(item1.getPayload())
                .as("item-1 must have extended validity (Phase 2 propagation)")
                .contains("2026-06-30")
                .doesNotContain("2025-12-31");
        assertThat(item1.getOfferIds()).contains("offer-A");

        // item-2: same — updated via Phase 2 DB column lookup
        assertThat(item2.getPayload())
                .as("item-2 must have extended validity (Phase 2 propagation)")
                .contains("2026-06-30")
                .doesNotContain("2025-12-31");
        assertThat(item2.getOfferIds()).contains("offer-A");
    }
}
