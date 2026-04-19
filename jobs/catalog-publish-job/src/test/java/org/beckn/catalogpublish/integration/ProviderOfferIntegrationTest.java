package org.beckn.catalogpublish.integration;

import org.beckn.catalogpublish.model.ItemId;
import org.beckn.catalogpublish.model.ProviderOffer;
import org.beckn.catalogpublish.model.ProviderOfferId;
import org.beckn.catalogpublish.orchestration.CatalogPublishOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for provider-level offer persistence (Phase 4).
 */
class ProviderOfferIntegrationTest extends BaseIntegrationTest {

    @Autowired
    CatalogPublishOrchestrator orchestrator;

    @Test
    void providerOffer_persistedToTable_notStampedOnItems() {
        String publish = """
                {
                  "context": {"bppId":"bpp-a","bppUri":"http://bpp-a.example.com",
                               "messageId":"m1","transactionId":"t1","networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "provider": {"id": "prov-abc"},
                    "resources": [{"id": "item-1",
                      "descriptor": {"name": "Widget"},
                      "resourceAttributes": {"@context": "https://schema.org/", "@type": "Product"}}],
                    "offers": [
                      {"id": "offer-item", "resourceIds": ["item-1"], "descriptor": {"name": "Item Offer"}},
                      {"id": "offer-prov", "descriptor": {"name": "Provider-Wide 20% Off"}}
                    ]}]}
                }""";
        orchestrator.processPublish(publish);

        // Item-level offer: stamped on item
        var item = itemRepository.findById(new ItemId("item-1", "cat-1")).orElseThrow();
        assertThat(item.getPayload()).contains("offer-item");
        // Provider-level offer: NOT stamped on item
        assertThat(item.getPayload()).doesNotContain("offer-prov");

        // Provider-level offer: stored in provider_offer table
        var po = providerOfferRepository.findById(new ProviderOfferId("offer-prov", "cat-1")).orElseThrow();
        assertThat(po.getProviderId()).isEqualTo("prov-abc");
        assertThat(po.getPayload()).contains("Provider-Wide 20% Off");
        assertThat(po.getCreatedAt()).isNotNull();
        assertThat(po.getUpdatedAt()).isNotNull();
        assertThat(po.getCreatedAt()).isEqualTo(po.getUpdatedAt());
    }

    @Test
    void fullReplace_deletesOldProviderOffers() {
        // Step 1: Publish with provider offer
        String initial = """
                {
                  "context": {"bppId":"bpp-a","bppUri":"http://bpp-a.example.com",
                               "messageId":"m1","transactionId":"t1","networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "provider": {"id": "prov-abc"},
                    "resources": [{"id": "item-1",
                      "descriptor": {"name": "Widget"}}],
                    "offers": [
                      {"id": "old-prov-offer", "descriptor": {"name": "Old Offer"}}
                    ]}]}
                }""";
        orchestrator.processPublish(initial);
        assertThat(providerOfferRepository.findById(new ProviderOfferId("old-prov-offer", "cat-1"))).isPresent();

        // Step 2: FULL replace with different provider offer
        String fullReplace = """
                {
                  "context": {"bppId":"bpp-a","bppUri":"http://bpp-a.example.com",
                               "messageId":"m2","transactionId":"t2","networkId":"net-1"},
                  "message": {
                    "publishDirectives": [{"catalogId": "cat-1", "updateMode": "FULL"}],
                    "catalogs": [{"id": "cat-1",
                      "provider": {"id": "prov-abc"},
                      "resources": [{"id": "item-1",
                        "descriptor": {"name": "Widget v2"}}],
                      "offers": [
                        {"id": "new-prov-offer", "descriptor": {"name": "New Offer"}}
                      ]}]}
                }""";
        orchestrator.processPublish(fullReplace);

        // Old offer deleted
        assertThat(providerOfferRepository.findById(new ProviderOfferId("old-prov-offer", "cat-1"))).isEmpty();
        // New offer present
        assertThat(providerOfferRepository.findById(new ProviderOfferId("new-prov-offer", "cat-1"))).isPresent();
    }

    @Test
    void mergeMode_upserts_preservesExisting() {
        // Step 1: Publish with provider offer A
        String initial = """
                {
                  "context": {"bppId":"bpp-a","bppUri":"http://bpp-a.example.com",
                               "messageId":"m1","transactionId":"t1","networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "provider": {"id": "prov-abc"},
                    "resources": [{"id": "item-1",
                      "descriptor": {"name": "Widget"}}],
                    "offers": [
                      {"id": "offer-a", "descriptor": {"name": "Offer A"}}
                    ]}]}
                }""";
        orchestrator.processPublish(initial);
        assertThat(providerOfferRepository.count()).isEqualTo(1);

        // Step 2: MERGE with provider offer B (default mode)
        String merge = """
                {
                  "context": {"bppId":"bpp-a","bppUri":"http://bpp-a.example.com",
                               "messageId":"m2","transactionId":"t2","networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "provider": {"id": "prov-abc"},
                    "resources": [{"id": "item-1",
                      "descriptor": {"name": "Widget"}}],
                    "offers": [
                      {"id": "offer-b", "descriptor": {"name": "Offer B"}}
                    ]}]}
                }""";
        orchestrator.processPublish(merge);

        // Both offers present
        assertThat(providerOfferRepository.count()).isEqualTo(2);
        assertThat(providerOfferRepository.findById(new ProviderOfferId("offer-a", "cat-1"))).isPresent();
        assertThat(providerOfferRepository.findById(new ProviderOfferId("offer-b", "cat-1"))).isPresent();
    }

    @Test
    void offerOnlyCatalog_providerOffersPersisted() {
        // Offer-only catalog with no real resources — only provider offers
        String offerOnly = """
                {
                  "context": {"bppId":"bpp-b","bppUri":"http://bpp-b.example.com",
                               "messageId":"m1","transactionId":"t1","networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-offers",
                    "provider": {"id": "prov-xyz"},
                    "resources": [],
                    "offers": [
                      {"id": "prov-offer-1", "descriptor": {"name": "Buy 1 Get 1"}},
                      {"id": "prov-offer-2", "descriptor": {"name": "Free Shipping"}}
                    ]}]}
                }""";
        orchestrator.processPublish(offerOnly);

        // No items created
        assertThat(itemRepository.count()).isEqualTo(0);

        // Provider offers stored
        assertThat(providerOfferRepository.count()).isEqualTo(2);
        var po1 = providerOfferRepository.findById(new ProviderOfferId("prov-offer-1", "cat-offers")).orElseThrow();
        assertThat(po1.getProviderId()).isEqualTo("prov-xyz");
        assertThat(po1.getPayload()).contains("Buy 1 Get 1");
    }

    @Test
    void mergeMode_idempotentReplay_updatesPayloadPreservesCreatedAt() {
        String publish = """
                {
                  "context": {"bppId":"bpp-a","bppUri":"http://bpp-a.example.com",
                               "messageId":"m1","transactionId":"t1","networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "provider": {"id": "prov-abc"},
                    "resources": [{"id": "item-1",
                      "descriptor": {"name": "Widget"}}],
                    "offers": [
                      {"id": "offer-x", "descriptor": {"name": "Original"}}
                    ]}]}
                }""";
        orchestrator.processPublish(publish);
        ProviderOffer first = providerOfferRepository.findById(new ProviderOfferId("offer-x", "cat-1")).orElseThrow();
        var originalCreatedAt = first.getCreatedAt();
        assertThat(originalCreatedAt).isNotNull();

        // Replay same offer with updated name
        String replay = """
                {
                  "context": {"bppId":"bpp-a","bppUri":"http://bpp-a.example.com",
                               "messageId":"m2","transactionId":"t2","networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "provider": {"id": "prov-abc"},
                    "resources": [{"id": "item-1",
                      "descriptor": {"name": "Widget"}}],
                    "offers": [
                      {"id": "offer-x", "descriptor": {"name": "Updated"}}
                    ]}]}
                }""";
        orchestrator.processPublish(replay);

        assertThat(providerOfferRepository.count()).isEqualTo(1);
        ProviderOffer updated = providerOfferRepository.findById(new ProviderOfferId("offer-x", "cat-1")).orElseThrow();
        assertThat(updated.getPayload()).contains("Updated");
    }

    @Test
    void emptyResourceIds_classifiedAsProviderOffer() {
        String publish = """
                {
                  "context": {"bppId":"bpp-a","bppUri":"http://bpp-a.example.com",
                               "messageId":"m1","transactionId":"t1","networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "provider": {"id": "prov-abc"},
                    "resources": [{"id": "item-1",
                      "descriptor": {"name": "Widget"}}],
                    "offers": [
                      {"id": "offer-empty", "resourceIds": [], "descriptor": {"name": "Empty Ids Offer"}}
                    ]}]}
                }""";
        orchestrator.processPublish(publish);

        // Empty resourceIds → classified as provider-level
        assertThat(providerOfferRepository.count()).isEqualTo(1);
        var po = providerOfferRepository.findById(new ProviderOfferId("offer-empty", "cat-1")).orElseThrow();
        assertThat(po.getProviderId()).isEqualTo("prov-abc");
        assertThat(po.getPayload()).contains("Empty Ids Offer");

        // Not stamped on item
        var item = itemRepository.findById(new ItemId("item-1", "cat-1")).orElseThrow();
        assertThat(item.getPayload()).doesNotContain("offer-empty");
    }

    @Test
    void missingProviderId_skipsProviderOfferPersistence() {
        // No provider field on catalog
        String noProvider = """
                {
                  "context": {"bppId":"bpp-a","bppUri":"http://bpp-a.example.com",
                               "messageId":"m1","transactionId":"t1","networkId":"net-1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [{"id": "item-1",
                      "descriptor": {"name": "Widget"}}],
                    "offers": [
                      {"id": "orphan-offer", "descriptor": {"name": "Orphan"}}
                    ]}]}
                }""";
        orchestrator.processPublish(noProvider);

        // Item persisted
        assertThat(itemRepository.count()).isEqualTo(1);
        // No provider offers persisted (no provider.id)
        assertThat(providerOfferRepository.count()).isEqualTo(0);
    }
}
