package org.beckn.catalogpublish.integration;

import org.beckn.catalogpublish.model.ItemId;
import org.beckn.catalogpublish.orchestration.CatalogPublishOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for offer-only catalog support (Phase 0 + Phase 3).
 *
 * <p>Phase 0: Resources without a {@code descriptor} are skipped — no garbage item rows.
 * <p>Phase 3: Offers referencing resources owned by other BPPs are merged into those items.
 */
class OfferOnlyPublishIntegrationTest extends BaseIntegrationTest {

    @Autowired
    CatalogPublishOrchestrator orchestrator;

    /**
     * Cross-BPP offer attachment happy path.
     * BPP-A publishes a real resource. BPP-B then publishes an offer-only catalog
     * referencing BPP-A's resource. The offer must be merged into BPP-A's item.
     */
    @Test
    void crossBppOfferAttachment_attachesOfferToOtherBppItem() {
        // Step 1: BPP-A publishes its resource
        String bppAPublish = """
                {
                  "context": {"bppId":"bpp-a","bppUri":"http://bpp-a.example.com",
                               "messageId":"m1","transactionId":"t1",
                               "networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-a",
                    "resources": [{"id": "item-ev-1",
                      "descriptor": {"name": "EV Charging Station"},
                      "resourceAttributes": {"@context": "https://schema.org/", "@type": "ChargingStation"}}],
                    "offers": []}]}
                }""";
        orchestrator.processPublish(bppAPublish);
        assertThat(itemRepository.count()).isEqualTo(1);
        var itemAfterA = itemRepository.findById(new ItemId("item-ev-1", "cat-a")).orElseThrow();
        assertThat(itemAfterA.getCatalogId()).isEqualTo("cat-a");
        assertThat(itemAfterA.getPayload()).doesNotContain("offer-discount-10");

        // Step 2: BPP-B publishes offer-only catalog referencing BPP-A's resource
        String bppBOfferOnly = """
                {
                  "context": {"bppId":"bpp-b","bppUri":"http://bpp-b.example.com",
                               "messageId":"m2","transactionId":"t2",
                               "networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-b",
                    "resources": [],
                    "offers": [{"id": "offer-discount-10",
                      "resourceIds": ["item-ev-1"],
                      "descriptor": {"name": "10% Discount on EV Charging"},
                      "discount": "10"}]}]}
                }""";
        var results = orchestrator.processPublish(bppBOfferOnly).results();

        // Only BPP-A's item exists; BPP-B created no new items
        assertThat(itemRepository.count()).isEqualTo(1);
        assertThat(results).hasSize(1);

        // BPP-A's item must now carry the offer, but retain its original catalog identity
        var itemAfterB = itemRepository.findById(new ItemId("item-ev-1", "cat-a")).orElseThrow();
        assertThat(itemAfterB.getCatalogId())
                .as("Item must retain catalog-A's identity — never overwritten by catalog-B")
                .isEqualTo("cat-a");
        assertThat(itemAfterB.getPayload())
                .as("Offer must be merged into the payload")
                .contains("offer-discount-10")
                .contains("10% Discount on EV Charging");
        assertThat(itemAfterB.getOfferIds())
                .as("offer_ids column must include the cross-BPP offer ID")
                .contains("offer-discount-10");
    }

    /**
     * Offer update via cross-BPP attach — discount changes from 10% to 20%.
     * Publishing the offer again must merge (RFC 7396), not duplicate the offer.
     */
    @Test
    void crossBppOfferUpdate_mergesOfferNotDuplicates() {
        // Step 1: BPP-A's resource
        String bppAPublish = """
                {
                  "context": {"bppId":"bpp-a","bppUri":"http://bpp-a.example.com",
                               "messageId":"m1","transactionId":"t1","networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-a",
                    "resources": [{"id": "item-ev-1",
                      "descriptor": {"name": "EV Charging Station"}}],
                    "offers": []}]}
                }""";
        orchestrator.processPublish(bppAPublish);

        // Step 2: BPP-B attaches offer with discount=10
        String bppBOffer10 = """
                {
                  "context": {"bppId":"bpp-b","bppUri":"http://bpp-b.example.com",
                               "messageId":"m2","transactionId":"t2","networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-b",
                    "resources": [],
                    "offers": [{"id": "offer-discount",
                      "resourceIds": ["item-ev-1"],
                      "descriptor": {"name": "Discount"},
                      "discount": "10"}]}]}
                }""";
        orchestrator.processPublish(bppBOffer10);
        var afterOffer10 = itemRepository.findById(new ItemId("item-ev-1", "cat-a")).orElseThrow();
        assertThat(afterOffer10.getPayload()).contains("\"discount\"").contains("\"10\"");

        // Step 3: BPP-B updates offer to discount=20
        String bppBOffer20 = """
                {
                  "context": {"bppId":"bpp-b","bppUri":"http://bpp-b.example.com",
                               "messageId":"m3","transactionId":"t3","networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-b",
                    "resources": [],
                    "offers": [{"id": "offer-discount",
                      "resourceIds": ["item-ev-1"],
                      "descriptor": {"name": "Discount"},
                      "discount": "20"}]}]}
                }""";
        orchestrator.processPublish(bppBOffer20);

        assertThat(itemRepository.count()).isEqualTo(1);
        var afterOffer20 = itemRepository.findById(new ItemId("item-ev-1", "cat-a")).orElseThrow();

        // Offer must be updated (not duplicated) — RFC 7396 merge
        assertThat(afterOffer20.getPayload())
                .as("Offer must be updated to discount=20 via RFC 7396 merge")
                .contains("\"discount\"").contains("\"20\"");
        assertThat(afterOffer20.getPayload())
                .as("Old discount value 10 must be gone after RFC 7396 merge")
                .doesNotContain("\"10\"");
        assertThat(afterOffer20.getOfferIds()).contains("offer-discount");

        // Only one offer entry in the payload — not duplicated
        long offerCount = countOccurrences(afterOffer20.getPayload(), "offer-discount");
        assertThat(offerCount)
                .as("offer-discount must appear exactly once in the payload (not duplicated)")
                .isEqualTo(1);
    }

    /**
     * Missing resourceId: BPP-B references a non-existent resource.
     * Must complete without failure — warning logged, metric incremented.
     */
    @Test
    void crossBppOffer_missingResourceId_completesWithoutFailure() {
        // BPP-B publishes offer referencing a resource that doesn't exist
        String bppBOfferOnly = """
                {
                  "context": {"bppId":"bpp-b","bppUri":"http://bpp-b.example.com",
                               "messageId":"m1","transactionId":"t1","networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-b",
                    "resources": [],
                    "offers": [{"id": "offer-x",
                      "resourceIds": ["non-existent-resource-id"],
                      "descriptor": {"name": "Some Offer"}}]}]}
                }""";

        // Must not throw — warning is acceptable, failure is not
        var outcome = orchestrator.processPublish(bppBOfferOnly);
        assertThat(outcome).isNotNull();

        // No items created
        assertThat(itemRepository.count()).isEqualTo(0);
    }

    /**
     * Mixed catalog: real resources + minimal reference resources + offers.
     * Real resources must be persisted, minimal references skipped, offers resolved.
     */
    @Test
    void mixedCatalog_realResourcesPersistedMinimalSkippedOffersResolved() {
        // Step 1: BPP-A's real resource
        String bppAPublish = """
                {
                  "context": {"bppId":"bpp-a","bppUri":"http://bpp-a.example.com",
                               "messageId":"m1","transactionId":"t1","networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-a",
                    "resources": [{"id": "item-real-a",
                      "descriptor": {"name": "Real Resource from BPP-A"}}],
                    "offers": []}]}
                }""";
        orchestrator.processPublish(bppAPublish);
        assertThat(itemRepository.count()).isEqualTo(1);

        // Step 2: BPP-B sends mixed catalog:
        //   - item-real-b: real resource (has descriptor) → must be persisted
        //   - item-minimal: no descriptor → must be skipped
        //   - offer referencing item-real-a (cross-BPP)
        String bppBMixed = """
                {
                  "context": {"bppId":"bpp-b","bppUri":"http://bpp-b.example.com",
                               "messageId":"m2","transactionId":"t2","networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-b",
                    "resources": [
                      {"id": "item-real-b",
                        "descriptor": {"name": "Real Resource from BPP-B"}},
                      {"id": "item-minimal",
                        "resourceAttributes": {"@context": "https://schema.org/", "@type": "TestType"}}
                    ],
                    "offers": [{"id": "offer-cross",
                      "resourceIds": ["item-real-a"],
                      "descriptor": {"name": "Cross-BPP Offer"}}]}]}
                }""";
        orchestrator.processPublish(bppBMixed);

        // Exactly 2 items: item-real-a (bpp-a) + item-real-b (bpp-b); item-minimal skipped
        assertThat(itemRepository.count())
                .as("item-minimal must be skipped; only 2 real items in DB")
                .isEqualTo(2);

        // item-real-b created by BPP-B
        var itemRealB = itemRepository.findById(new ItemId("item-real-b", "cat-b")).orElseThrow();
        assertThat(itemRealB.getPayload()).contains("Real Resource from BPP-B");

        // item-minimal must NOT exist
        assertThat(itemRepository.findById(new ItemId("item-minimal", "cat-b"))).isEmpty();

        // item-real-a from BPP-A must have cross-BPP offer attached
        var itemRealA = itemRepository.findById(new ItemId("item-real-a", "cat-a")).orElseThrow();
        assertThat(itemRealA.getPayload())
                .as("Cross-BPP offer must be merged into BPP-A's item")
                .contains("offer-cross").contains("Cross-BPP Offer");
        assertThat(itemRealA.getOfferIds()).contains("offer-cross");
    }

    /**
     * Existing PatchFlow tests must be unaffected — verifies no regression from Phase 0 change.
     * Resources with descriptor are still persisted normally.
     */
    @Test
    void regression_resourcesWithDescriptorStillPersistedNormally() {
        String publish = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m1","transactionId":"t1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [{"id": "item-1",
                      "descriptor": {"name": "EV Station"}}],
                    "offers": []}]}
                }""";
        orchestrator.processPublish(publish);
        assertThat(itemRepository.count()).isEqualTo(1);
        var item = itemRepository.findAll().get(0);
        assertThat(item.getId()).isEqualTo("item-1");
        assertThat(item.getCatalogId()).isEqualTo("cat-1");
        assertThat(item.getPayload()).contains("EV Station");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private long countOccurrences(String text, String substring) {
        if (text == null || text.isEmpty() || substring.isEmpty()) return 0;
        long count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substring, idx)) != -1) {
            count++;
            idx += substring.length();
        }
        return count;
    }
}
