package org.beckn.catalogpublish.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.metrics.CatalogPublishMetrics;
import org.beckn.catalogpublish.model.Item;
import org.beckn.catalogpublish.service.payload.ItemPayloadBuilder;
import org.beckn.catalogpublish.service.payload.PayloadMergeService;
import org.beckn.catalogpublish.store.ItemStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OfferResolutionStepTest {

    @Mock
    private ItemStore itemStore;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PayloadMergeService mergeService;
    private ItemPayloadBuilder payloadBuilder;
    private CatalogPublishMetrics metrics;
    private MeterRegistry registry;
    private OfferResolutionStep step;

    @BeforeEach
    void setUp() {
        mergeService = new PayloadMergeService(objectMapper);
        payloadBuilder = new ItemPayloadBuilder(objectMapper);
        registry = new SimpleMeterRegistry();
        metrics = new CatalogPublishMetrics(registry);
        step = new OfferResolutionStep(itemStore, mergeService, payloadBuilder, metrics);
    }

    private CatalogContext testCtx() {
        return new CatalogContext(List.of("net-1"), "sub-b", null, objectMapper.createObjectNode());
    }

    private Item buildStoredItem(String id, String bppId, String catalogId, String payloadJson) {
        return Item.from(id, payloadJson, new String[0], null, bppId,
                catalogId, "TestType", null, new String[]{"net-1"});
    }

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    void resolveCrossBppOffers_attachesOfferToExistingItem() throws Exception {
        // BPP-A's item stored in DB with a minimal denorm payload
        String existingPayload = objectMapper.writeValueAsString(
                objectMapper.readTree("""
                    {"catalogs":[{"id":"cat-a","resources":[{"id":"item-a","descriptor":{"name":"Charging Station"}}],"offers":[]}]}
                """));
        Item storedItem = buildStoredItem("item-a", "bpp-a", "cat-a", existingPayload);
        when(itemStore.findAllByIdIn(anyList())).thenReturn(List.of(storedItem));

        // BPP-B's offer referencing item-a
        JsonNode offer = objectMapper.readTree("""
            {"id":"offer-x","resourceIds":["item-a"],"descriptor":{"name":"Discount 10%"},"discount":"10"}
        """);
        Map<String, JsonNode> offerMap = Map.of("offer-x", offer);

        var results = step.resolveCrossBppOffers(offerMap, Set.of(), testCtx());

        assertThat(results).hasSize(1);
        var resolved = results.get(0);
        // Catalog identity must be preserved from the original item, NOT from the publishing catalog
        assertThat(resolved.item().getCatalogId()).isEqualTo("cat-a");
        assertThat(resolved.item().getId()).isEqualTo("item-a");
        // Offer must be merged into the payload
        assertThat(resolved.item().getPayload()).contains("offer-x");
        assertThat(resolved.item().getPayload()).contains("Discount 10%");
        // offer_ids column must include the new offer
        assertThat(resolved.item().getOfferIds()).contains("offer-x");
    }

    // ── Missing resourceIds ────────────────────────────────────────────────────

    @Test
    void resolveCrossBppOffers_missingResourceId_logsWarnAndReturnsEmpty() throws Exception {
        when(itemStore.findAllByIdIn(anyList())).thenReturn(List.of());

        JsonNode offer = objectMapper.readTree("""
            {"id":"offer-x","resourceIds":["non-existent-id"],"descriptor":{"name":"Offer"}}
        """);

        var results = step.resolveCrossBppOffers(Map.of("offer-x", offer), Set.of(), testCtx());

        assertThat(results).isEmpty();
        // Missing counter must have been incremented
        assertThat(registry.counter("discovr.publish.offer.resolve.missing").count()).isEqualTo(1.0);
        assertThat(registry.counter("discovr.publish.offer.resolve.success").count()).isEqualTo(0.0);
    }

    // ── Empty offers map ───────────────────────────────────────────────────────

    @Test
    void resolveCrossBppOffers_emptyOffersMap_returnsImmediately() {
        var results = step.resolveCrossBppOffers(Map.of(), Set.of(), testCtx());

        assertThat(results).isEmpty();
        // Should not even touch the DB
        verify(itemStore, never()).findAllByIdIn(anyList());
    }

    // ── Already-handled IDs skipped ───────────────────────────────────────────

    @Test
    void resolveCrossBppOffers_alreadyHandledIdSkipped() throws Exception {
        JsonNode offer = objectMapper.readTree("""
            {"id":"offer-x","resourceIds":["item-already-handled"],"descriptor":{"name":"Offer"}}
        """);

        // item-already-handled was processed in Phase 1 or Phase 2
        Set<String> alreadyHandled = Set.of("item-already-handled");
        var results = step.resolveCrossBppOffers(Map.of("offer-x", offer), alreadyHandled, testCtx());

        assertThat(results).isEmpty();
        // DB must not be queried — nothing left to resolve
        verify(itemStore, never()).findAllByIdIn(anyList());
    }

    // ── Offer with no resourceIds field ───────────────────────────────────────

    @Test
    void resolveCrossBppOffers_offerWithoutResourceIds_returnsEmpty() throws Exception {
        JsonNode offer = objectMapper.readTree("""
            {"id":"offer-x","descriptor":{"name":"Offer"}}
        """);
        // No resourceIds field → all resource ID collection yields empty → returns early
        var results = step.resolveCrossBppOffers(Map.of("offer-x", offer), Set.of(), testCtx());

        assertThat(results).isEmpty();
        verify(itemStore, never()).findAllByIdIn(anyList());
    }

    // ── Partial match: some IDs found, some missing ────────────────────────────

    @Test
    void resolveCrossBppOffers_partialMatch_updatesFoundItems() throws Exception {
        String existingPayload = objectMapper.writeValueAsString(
                objectMapper.readTree("""
                    {"catalogs":[{"id":"cat-a","resources":[{"id":"item-a","descriptor":{"name":"Station"}}],"offers":[]}]}
                """));
        Item storedItem = buildStoredItem("item-a", "bpp-a", "cat-a", existingPayload);
        when(itemStore.findAllByIdIn(anyList())).thenReturn(List.of(storedItem));

        JsonNode offer = objectMapper.readTree("""
            {"id":"offer-x","resourceIds":["item-a","item-missing"],"descriptor":{"name":"Bundle"}}
        """);

        var results = step.resolveCrossBppOffers(Map.of("offer-x", offer), Set.of(), testCtx());

        // Only item-a resolved successfully
        assertThat(results).hasSize(1);
        assertThat(results.get(0).item().getId()).isEqualTo("item-a");
        // Missing counter incremented for item-missing
        assertThat(registry.counter("discovr.publish.offer.resolve.missing").count()).isEqualTo(1.0);
        assertThat(registry.counter("discovr.publish.offer.resolve.success").count()).isEqualTo(1.0);
    }

    // ── Preserves existing offer IDs when merging new ones ────────────────────

    @Test
    void resolveCrossBppOffers_mergesNewOfferIdsWithExisting() throws Exception {
        String existingPayload = objectMapper.writeValueAsString(
                objectMapper.readTree("""
                    {"catalogs":[{"id":"cat-a","resources":[{"id":"item-a","descriptor":{"name":"Station"}}],
                    "offers":[{"id":"offer-old","descriptor":{"name":"Old Offer"}}]}]}
                """));
        // Item already has offer-old in the DB
        Item storedItem = Item.from("item-a", existingPayload, new String[]{"offer-old"}, null, "sub-a",
                "cat-a", "TestType", null, new String[]{"net-1"});
        when(itemStore.findAllByIdIn(anyList())).thenReturn(List.of(storedItem));

        JsonNode newOffer = objectMapper.readTree("""
            {"id":"offer-new","resourceIds":["item-a"],"descriptor":{"name":"New Offer"}}
        """);

        var results = step.resolveCrossBppOffers(Map.of("offer-new", newOffer), Set.of(), testCtx());

        assertThat(results).hasSize(1);
        var resolved = results.get(0);
        // Both old and new offer IDs must be present
        assertThat(resolved.item().getOfferIds()).contains("offer-old", "offer-new");
        assertThat(resolved.item().getPayload()).contains("offer-old").contains("offer-new");
    }
}
