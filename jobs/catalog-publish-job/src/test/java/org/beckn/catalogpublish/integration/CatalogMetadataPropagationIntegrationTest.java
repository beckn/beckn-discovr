package org.beckn.catalogpublish.integration;

import org.beckn.catalogpublish.model.ItemId;
import org.beckn.catalogpublish.orchestration.CatalogPublishOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Phase 3.5 — catalog metadata propagation.
 *
 * <p>Catalog-level properties (descriptor, provider, validity, …) have no table of their own:
 * every item row carries its own copy. A MERGE publish that changes them while listing only some
 * of the catalog's resources must still reach the rest, or the same catalog answers discover with
 * two different identities.
 */
class CatalogMetadataPropagationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    CatalogPublishOrchestrator orchestrator;

    /**
     * Baseline for the catalog-metadata propagation tests (Phase 3.5): cat-meta holds res-1
     * and res-2, the catalog carries a descriptor name and a provider, and res-2 has an offer
     * so the tests can prove propagation does not disturb it.
     */
    private static final String META_BASELINE = """
            {
              "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                           "messageId":"m1","transactionId":"t1"},
              "message": {"catalogs": [{
                "id": "cat-meta",
                "descriptor": {"name": "Catalog Original"},
                "provider": {"id": "prov-1", "descriptor": {"name": "Provider Original"}},
                "resources": [
                  {"id": "res-1", "descriptor": {"name": "Resource One"}},
                  {"id": "res-2", "descriptor": {"name": "Resource Two"}}
                ],
                "offers": [
                  {"id": "offer-1", "descriptor": {"name": "Ten Percent Off"},
                   "resourceIds": ["res-2"]}
                ]}]}
            }""";

    /**
     * MERGE mode: a publish that changes catalog-level metadata while listing only some of the
     * catalog's resources must refresh the resources it did NOT list.
     *
     * <p>Catalog properties have no table of their own — every item row carries a copy — so
     * without Phase 3.5 the unlisted rows keep the old catalog name and provider, and the same
     * catalog answers discover with two different identities.
     */
    @Test
    void mergeMode_catalogMetadataChanged_propagatesToUnlistedResources() {
        orchestrator.processPublish(META_BASELINE);

        // Round 2: new catalog name + provider, and only res-1 in the payload.
        String round2 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {"catalogs": [{
                    "id": "cat-meta",
                    "descriptor": {"name": "Catalog Renamed"},
                    "provider": {"id": "prov-1", "descriptor": {"name": "Provider Renamed"}},
                    "resources": [
                      {"id": "res-1", "descriptor": {"name": "Resource One Updated"}}
                    ],
                    "offers": []}]}
                }""";
        orchestrator.processPublish(round2);

        assertThat(itemRepository.count()).isEqualTo(2);

        var res1 = itemRepository.findById(new ItemId("res-1", "cat-meta")).orElseThrow();
        assertThat(res1.getPayload()).contains("Catalog Renamed", "Provider Renamed", "Resource One Updated");

        var res2 = itemRepository.findById(new ItemId("res-2", "cat-meta")).orElseThrow();
        assertThat(res2.getPayload())
                .as("res-2 was not listed, but must still carry the new catalog metadata")
                .contains("Catalog Renamed", "Provider Renamed")
                .doesNotContain("Catalog Original", "Provider Original");
        assertThat(res2.getPayload())
                .as("propagation refreshes catalog metadata only — res-2 keeps its own resource body")
                .contains("Resource Two");
        assertThat(res2.getOfferIds())
                .as("propagation must not disturb offers the publish never mentioned")
                .containsExactly("offer-1");
        assertThat(res2.getPayload()).contains("Ten Percent Off");
    }

    /**
     * MERGE mode: when catalog metadata is unchanged, unlisted resources are left exactly as
     * they were. Guards against Phase 3.5 rewriting rows it has no reason to touch.
     */
    @Test
    void mergeMode_catalogMetadataUnchanged_leavesUnlistedResourcesIntact() {
        orchestrator.processPublish(META_BASELINE);
        String storedBefore = itemRepository.findById(new ItemId("res-2", "cat-meta"))
                .orElseThrow().getPayload();

        // Same catalog metadata, only res-1 restated.
        String round2 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {"catalogs": [{
                    "id": "cat-meta",
                    "descriptor": {"name": "Catalog Original"},
                    "provider": {"id": "prov-1", "descriptor": {"name": "Provider Original"}},
                    "resources": [
                      {"id": "res-1", "descriptor": {"name": "Resource One Updated"}}
                    ],
                    "offers": []}]}
                }""";
        orchestrator.processPublish(round2);

        assertThat(itemRepository.count()).isEqualTo(2);
        assertThat(itemRepository.findById(new ItemId("res-2", "cat-meta")).orElseThrow().getPayload())
                .as("metadata unchanged — res-2 must be byte-identical")
                .isEqualTo(storedBefore);
    }

    /**
     * An offer-only publish names the catalog as a bare reference — {@code {"id": …}} with no
     * descriptor or provider — only to say which catalog the offers belong to. Propagating that
     * would erase the real catalog metadata from every row, so it must be left alone.
     */
    @Test
    void mergeMode_offerOnlyPublish_doesNotWipeCatalogMetadata() {
        orchestrator.processPublish(META_BASELINE);
        String res2Before = itemRepository.findById(new ItemId("res-2", "cat-meta"))
                .orElseThrow().getPayload();

        // Offer-only publish: catalog carried as a reference, no descriptor, no provider.
        String offerOnly = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {"catalogs": [{
                    "id": "cat-meta",
                    "resources": [],
                    "offers": [
                      {"id": "offer-2", "descriptor": {"name": "Flash Sale"},
                       "resourceIds": ["res-1"]}
                    ]}]}
                }""";
        orchestrator.processPublish(offerOnly);

        assertThat(itemRepository.findById(new ItemId("res-2", "cat-meta")).orElseThrow().getPayload())
                .as("an offer-only publish says nothing about catalog metadata — res-2 stays as it was")
                .isEqualTo(res2Before);
        assertThat(itemRepository.findById(new ItemId("res-1", "cat-meta")).orElseThrow().getPayload())
                .as("res-1 keeps its catalog metadata and gains the new offer")
                .contains("Catalog Original", "Provider Original", "Flash Sale");
    }

    /**
     * Catalog-metadata propagation is scoped to the publishing catalog: a rename on cat-meta
     * must not touch rows of another catalog, even one sharing a resource id.
     */
    @Test
    void mergeMode_catalogMetadataPropagation_scopedToPublishingCatalog() {
        orchestrator.processPublish(META_BASELINE);
        String other = """
                {
                  "context": {"bppId":"bpp-2","bppUri":"http://bpp2.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {"catalogs": [{
                    "id": "cat-other",
                    "descriptor": {"name": "Other Catalog"},
                    "provider": {"id": "prov-2", "descriptor": {"name": "Other Provider"}},
                    "resources": [{"id": "res-2", "descriptor": {"name": "Other Resource Two"}}],
                    "offers": []}]}
                }""";
        orchestrator.processPublish(other);

        String round3 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m3","transactionId":"t3"},
                  "message": {"catalogs": [{
                    "id": "cat-meta",
                    "descriptor": {"name": "Catalog Renamed"},
                    "provider": {"id": "prov-1", "descriptor": {"name": "Provider Renamed"}},
                    "resources": [{"id": "res-1", "descriptor": {"name": "Resource One Updated"}}],
                    "offers": []}]}
                }""";
        orchestrator.processPublish(round3);

        assertThat(itemRepository.findById(new ItemId("res-2", "cat-meta")).orElseThrow().getPayload())
                .contains("Catalog Renamed");
        assertThat(itemRepository.findById(new ItemId("res-2", "cat-other")).orElseThrow().getPayload())
                .as("cat-other must be untouched by a cat-meta rename")
                .contains("Other Catalog", "Other Provider")
                .doesNotContain("Catalog Renamed", "Provider Renamed");
    }
}
