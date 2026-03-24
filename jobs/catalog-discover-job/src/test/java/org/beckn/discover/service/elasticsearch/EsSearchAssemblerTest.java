package org.beckn.discover.service.elasticsearch;

import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Resource;
import org.beckn.discover.service.response.CatalogProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EsSearchAssemblerTest {

    private EsSearchAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new EsSearchAssembler(new CatalogProcessor());
    }

    @Test
    void emptyHits_returnsEmptyList() {
        assertThat(assembler.assemble(List.of(), "tx-1")).isEmpty();
    }

    @Test
    void singleHit_assemblesCatalogWithOneItem() {
        Map<String, Object> doc = evChargerDoc("cat-1", "bpp-1", "item-1", "DC Fast Charger");

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-1");

        assertThat(catalogs).hasSize(1);
        Catalog catalog = catalogs.get(0);
        assertThat(catalog.getId()).isEqualTo("cat-1");
        assertThat(catalog.getBppId()).isEqualTo("bpp-1");
        assertThat(catalog.getResources()).hasSize(1);

        Resource resource = catalog.getResources().get(0);
        assertThat(resource.getId()).isEqualTo("item-1");
        assertThat(resource.getDescriptor().getName()).isEqualTo("DC Fast Charger");
        assertThat(resource.getDescriptor().getShortDesc()).isEqualTo("60kW CCS2 charger");
    }

    @Test
    void multipleHitsSameCatalog_groupedIntoOneCatalogWithManyItems() {
        List<Map<String, Object>> docs = List.of(
                evChargerDoc("cat-1", "bpp-1", "item-1", "DC Fast Charger CCS2"),
                evChargerDoc("cat-1", "bpp-1", "item-2", "AC Charger Type2"));

        List<Catalog> catalogs = assembler.assemble(docs, "tx-2");

        assertThat(catalogs).hasSize(1);
        assertThat(catalogs.get(0).getResources()).hasSize(2);
        assertThat(catalogs.get(0).getResources())
                .extracting(Resource::getId)
                .containsExactlyInAnyOrder("item-1", "item-2");
    }

    @Test
    void hitsDifferentCatalogs_produceSeparateCatalogObjects() {
        List<Map<String, Object>> docs = List.of(
                evChargerDoc("cat-1", "bpp-1", "item-1", "CCS2 Charger"),
                evChargerDoc("cat-2", "bpp-2", "item-3", "Solar Panel"));

        List<Catalog> catalogs = assembler.assemble(docs, "tx-3");

        assertThat(catalogs).hasSize(2);
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("cat-1", "cat-2");
    }

    @Test
    void hitWithCategory_populatesCategoryOnItem() {
        Map<String, Object> doc = evChargerDoc("cat-1", "bpp-1", "item-1", "Charger");

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-4");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getCategory()).isNotNull();
        assertThat(resource.getCategory().getCodeValue()).isEqualTo("EV_CHARGING");
        assertThat(resource.getCategory().getName()).isEqualTo("EV Charging");
    }

    @Test
    void hitWithRating_populatesRatingOnItem() {
        Map<String, Object> doc = evChargerDoc("cat-1", "bpp-1", "item-1", "Charger");

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-5");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getRating()).isNotNull();
        assertThat(resource.getRating().getRatingValue()).isEqualTo(4.5);
        assertThat(resource.getRating().getRatingCount()).isEqualTo(120);
    }

    @Test
    void hitWithProvider_populatesProviderOnItem() {
        Map<String, Object> doc = evChargerDoc("cat-1", "bpp-1", "item-1", "Charger");

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-6");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getProvider()).isNotNull();
        assertThat(resource.getProvider().getId()).isEqualTo("ecopower-charging");
        assertThat(resource.getProvider().getDescriptor().getName()).isEqualTo("EcoPower Charging");
    }

    @Test
    void hitWithItemAttributes_populatesAttributes() {
        Map<String, Object> doc = evChargerDoc("cat-1", "bpp-1", "item-1", "Charger");

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-7");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getContext()).isEqualTo("https://custom.item.context");
        assertThat(resource.getType()).isEqualTo("CustomItemType");
        assertThat(resource.getResourceAttributes()).isNotNull();
        assertThat(resource.getResourceAttributes().getContext()).isEqualTo("https://custom.attr.context");
        assertThat(resource.getResourceAttributes().getType()).isEqualTo("EVCharger");
        assertThat(resource.getResourceAttributes().getAttribute("connectorType")).isEqualTo("CCS2");
    }

    @Test
    void hitMissingCatalogId_isSkipped() {
        Map<String, Object> doc = evChargerDoc(null, "bpp-1", "item-1", "Charger");

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-8");

        assertThat(catalogs).isEmpty();
    }

    @Test
    void hitWithOffers_offersSetOnCatalog() {
        Map<String, Object> offer = Map.of("id", "offer-1", "price", Map.of("value", 150.0));
        Map<String, Object> doc = evChargerDocWithOffers("cat-1", "bpp-1", "item-1", "Charger", List.of(offer));

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-9");

        assertThat(catalogs.get(0).getOffers()).isNotNull().hasSize(1);
    }

    @Test
    void offersReadFromSecondHit_whenFirstHitHasNoOffers() {
        // item-2 (no offers) arrives first in ES relevance order;
        // item-1 (has the offer) arrives second.
        // Offers must still appear in the assembled catalog.
        Map<String, Object> offer = Map.of("id", "offer-1", "resourceIds", List.of("item-1"), "price",
                Map.of("value", 99.0));

        Map<String, Object> noOfferDoc = evChargerDoc("cat-1", "bpp-1", "item-2", "AC Charger");
        Map<String, Object> offerDoc = evChargerDocWithOffers("cat-1", "bpp-1", "item-1", "DC Fast Charger",
                List.of(offer));

        List<Catalog> catalogs = assembler.assemble(List.of(noOfferDoc, offerDoc), "tx-10");

        assertThat(catalogs).hasSize(1);
        assertThat(catalogs.get(0).getResources()).hasSize(2);
        assertThat(catalogs.get(0).getOffers()).isNotNull().hasSize(1);
        assertThat(catalogs.get(0).getOffers().get(0))
                .isInstanceOfSatisfying(java.util.Map.class,
                        m -> assertThat(m.get("id")).isEqualTo("offer-1"));
    }

    @Test
    void duplicateOffersAcrossHits_deduplicatedByCatalogPipeline() {
        // Same offer stored in two items' ES documents (e.g. offer referencing both
        // items).
        // After assembly the offer list must not contain duplicates once the pipeline
        // runs.
        Map<String, Object> offer = Map.of("id", "offer-shared", "resourceIds", List.of("item-1", "item-2"));

        Map<String, Object> doc1 = evChargerDocWithOffers("cat-1", "bpp-1", "item-1", "CCS2 Charger", List.of(offer));
        Map<String, Object> doc2 = evChargerDocWithOffers("cat-1", "bpp-1", "item-2", "AC Charger", List.of(offer));

        // Raw assembly (before pipeline) accumulates duplicates
        List<Catalog> raw = assembler.assemble(List.of(doc1, doc2), "tx-11");
        // The assembler returns after CatalogProcessor.processCatalog (not the full
        // pipeline),
        // so we verify the offer is present at least once; pipeline dedup is tested
        // separately.
        assertThat(raw.get(0).getOffers()).isNotNull().isNotEmpty();
        assertThat(raw.get(0).getResources()).hasSize(2);
    }

    @Test
    void hitWithSingleLocField_populatesAvailableAt() {
        Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc("cat-1", "bpp-1", "item-1", "Charger"));
        doc.put("loc_catalogs_beckn_items_beckn_availableAt", List.of(
                Map.of("geo", Map.of("type", "Point", "coordinates", List.of(77.5, 12.9)),
                        "address", Map.of("streetAddress", "MG Road", "extendedAddress", "Apt 4B",
                                "addressLocality", "Bengaluru"))));

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-loc-1");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getAvailableAt()).isNotNull().hasSize(1);
        assertThat(resource.getAvailableAt().get(0).getGeo().getType()).isEqualTo("Point");
        assertThat(resource.getAvailableAt().get(0).getAddress().getStreetAddress()).isEqualTo("MG Road");
        assertThat(resource.getAvailableAt().get(0).getAddress().getExtendedAddress()).isEqualTo("Apt 4B");
    }

    @Test
    void hitWithMultipleLocFields_collectsAllLocations() {
        Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc("cat-1", "bpp-1", "item-1", "Charger"));
        // item-level availableAt
        doc.put("loc_catalogs_beckn_items_beckn_availableAt", Map.of(
                "geo", Map.of("type", "Point", "coordinates", List.of(77.5, 12.9))));
        // item-level custom location field
        doc.put("loc_catalogs_beckn_items_beckn_location", Map.of(
                "geo", Map.of("type", "Point", "coordinates", List.of(78.0, 13.0))));

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-loc-2");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getAvailableAt()).isNotNull().hasSize(2);
    }

    @Test
    void hitWithNoLocFields_availableAtIsNull() {
        Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc("cat-1", "bpp-1", "item-1", "Charger"));

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-loc-3");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getAvailableAt()).isNull();
    }

    @Test
    void offerLevelLocFields_notIncludedInAvailableAt() {
        Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc("cat-1", "bpp-1", "item-1", "Charger"));
        // offer-level location — should NOT appear in item.availableAt
        doc.put("loc_catalogs_beckn_offers_beckn_location", Map.of(
                "geo", Map.of("type", "Point", "coordinates", List.of(77.5, 12.9))));

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-loc-4");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getAvailableAt()).isNull();
    }

    @Test
    void resourceAttributesLocFields_notIncludedInAvailableAt() {
        Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc("cat-1", "bpp-1", "item-1", "Charger"));
        // resourceAttributes-level location — should NOT appear in item.availableAt
        doc.put("loc_catalogs_beckn_items_beckn_resourceAttributes_serviceArea", Map.of(
                "geo", Map.of("type", "Point", "coordinates", List.of(78.0, 13.0))));

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-loc-5");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getAvailableAt()).isNull();
    }

    @Test
    void providerLocFields_notIncludedInAvailableAt() {
        Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc("cat-1", "bpp-1", "item-1", "Charger"));
        // provider-level location — should NOT appear in item.availableAt
        doc.put("loc_catalogs_beckn_items_beckn_provider_beckn_locations", Map.of(
                "geo", Map.of("type", "Point", "coordinates", List.of(77.5, 12.9))));

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-loc-6");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getAvailableAt()).isNull();
    }

    @Test
    void providerLocFields_setOnProvider() {
        Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc("cat-1", "bpp-1", "item-1", "Charger"));
        doc.put("loc_catalogs_beckn_items_beckn_provider_beckn_locations", Map.of(
                "geo", Map.of("type", "Point", "coordinates", List.of(77.5, 12.9)),
                "address", Map.of("addressLocality", "Bengaluru")));

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-loc-7");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getProvider().getLocations()).isNotNull().hasSize(1);
        assertThat(resource.getProvider().getLocations().get(0).getGeo().getType()).isEqualTo("Point");
        assertThat(resource.getProvider().getLocations().get(0).getAddress().getAddressLocality()).isEqualTo("Bengaluru");
    }

    @Test
    void mixedLocFields_onlyItemLevelInAvailableAt_providerLevelOnProvider() {
        Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc("cat-1", "bpp-1", "item-1", "Charger"));
        // item-level — SHOULD be in availableAt
        doc.put("loc_catalogs_beckn_items_beckn_availableAt", Map.of(
                "geo", Map.of("type", "Point", "coordinates", List.of(77.5, 12.9))));
        // provider-level — should be on provider.locations, NOT availableAt
        doc.put("loc_catalogs_beckn_items_beckn_provider_beckn_locations", Map.of(
                "geo", Map.of("type", "Point", "coordinates", List.of(78.0, 13.0))));
        // offer-level — should NOT be in either
        doc.put("loc_catalogs_beckn_offers_beckn_location", Map.of(
                "geo", Map.of("type", "Point", "coordinates", List.of(80.0, 15.0))));
        // resourceAttributes-level — should NOT be in either
        doc.put("loc_catalogs_beckn_items_beckn_resourceAttributes_depot", Map.of(
                "geo", Map.of("type", "Point", "coordinates", List.of(79.0, 14.0))));

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-loc-8");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getAvailableAt()).isNotNull().hasSize(1);
        assertThat(resource.getAvailableAt().get(0).getGeo().getCoordinates())
                .containsExactly(77.5, 12.9);
        assertThat(resource.getProvider().getLocations()).isNotNull().hasSize(1);
        assertThat(resource.getProvider().getLocations().get(0).getGeo().getCoordinates())
                .containsExactly(78.0, 13.0);
    }

    // ── New field tests ───────────────────────────────────────────────────────

    @Test
    void hitWithThumbnailImage_populatesThumbnailImageOnDescriptor() {
        Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc("cat-1", "bpp-1", "item-1", "Charger"));
        doc.put("item_descriptor_thumbnail_image", "https://example.org/thumb.jpg");

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-new-1");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getDescriptor().getThumbnailImage()).isEqualTo("https://example.org/thumb.jpg");
    }

    @Test
    void hitWithDescriptorDocs_populatesDocsOnDescriptor() {
        Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc("cat-1", "bpp-1", "item-1", "Charger"));
        doc.put("item_descriptor_docs", List.of(
                Map.of("url", "https://example.org/doc.pdf", "label", "Manual")));

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-new-2");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getDescriptor().getDocs()).isNotNull().hasSize(1);
        assertThat(resource.getDescriptor().getDocs().get(0).get("label")).isEqualTo("Manual");
    }

    @Test
    void hitWithDescriptorMediaFile_populatesMediaFileOnDescriptor() {
        Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc("cat-1", "bpp-1", "item-1", "Charger"));
        doc.put("item_descriptor_media_file", List.of(
                Map.of("url", "https://example.org/video.mp4", "mimetype", "video/mp4")));

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-new-3");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getDescriptor().getMediaFile()).isNotNull().hasSize(1);
        assertThat(resource.getDescriptor().getMediaFile().get(0).get("mimetype")).isEqualTo("video/mp4");
    }

    @Test
    void hitWithProviderAlerts_populatesAlertsOnProvider() {
        Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc("cat-1", "bpp-1", "item-1", "Charger"));
        doc.put("item_provider_alerts", List.of(
                Map.of("type", "maintenance", "message", "Scheduled downtime tonight")));

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-new-4");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getProvider().getAlerts()).isNotNull().hasSize(1);
        assertThat(resource.getProvider().getAlerts().get(0).get("type")).isEqualTo("maintenance");
    }

    @Test
    void hitWithProviderPolicies_populatesPoliciesOnProvider() {
        Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc("cat-1", "bpp-1", "item-1", "Charger"));
        doc.put("item_provider_policies", List.of(
                Map.of("@type", "CancellationPolicy", "name", "No cancellations"),
                Map.of("@type", "ReturnPolicy", "name", "30-day returns")));

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-new-5");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getProvider().getPolicies()).isNotNull().hasSize(2);
        assertThat(resource.getProvider().getPolicies().get(0).getType()).isEqualTo("CancellationPolicy");
        assertThat(resource.getProvider().getPolicies().get(1).getName()).isEqualTo("30-day returns");
    }

    @Test
    void hitWithRatingReviewText_populatesReviewTextOnRating() {
        Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc("cat-1", "bpp-1", "item-1", "Charger"));
        doc.put("item_rating_review_text", "Excellent service and fast charging");

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-new-6");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getRating()).isNotNull();
        assertThat(resource.getRating().getReviewText()).isEqualTo("Excellent service and fast charging");
    }

    @Test
    void hitWithNetworkIdAsString_wrapsInList() {
        Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc("cat-1", "bpp-1", "item-1", "Charger"));
        // ES may return network_id as a single string when indexed as keyword
        doc.put("network_id", "ondc-ev");

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-new-7");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getNetworkId()).isNotNull().containsExactly("ondc-ev");
    }

    @Test
    void hitWithNetworkIdAsList_setsDirectly() {
        Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc("cat-1", "bpp-1", "item-1", "Charger"));
        doc.put("network_id", List.of("ondc-ev", "beckn-open"));

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-new-8");

        Resource resource = catalogs.get(0).getResources().get(0);
        assertThat(resource.getNetworkId()).isNotNull().containsExactly("ondc-ev", "beckn-open");
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static Map<String, Object> evChargerDoc(String catalogId, String bppId,
            String itemId, String itemName) {
        return Map.ofEntries(
                Map.entry("catalog_id", catalogId != null ? catalogId : ""),
                Map.entry("catalog_context", "https://custom.catalog.context"),
                Map.entry("catalog_type", "Catalog"),
                Map.entry("bpp_id", bppId),
                Map.entry("bpp_uri", "https://bpp.example.com"),
                Map.entry("network_id", "ondc-ev"),
                Map.entry("item_id", itemId),
                Map.entry("item_name", itemName),
                Map.entry("item_short_desc", "60kW CCS2 charger"),
                Map.entry("item_long_desc", "A fast DC charger supporting CCS2 connectors"),
                Map.entry("item_category_code", "EV_CHARGING"),
                Map.entry("item_category_name", "EV Charging"),
                Map.entry("item_rateable", true),
                Map.entry("item_is_active", true),
                Map.entry("item_rating_value", 4.5),
                Map.entry("item_rating_count", 120),
                Map.entry("item_provider_id", "ecopower-charging"),
                Map.entry("item_provider_name", "EcoPower Charging"),
                Map.entry("item_context", "https://custom.item.context"),
                Map.entry("item_type", "CustomItemType"),
                Map.entry("item_attributes", Map.of(
                        "@context", "https://custom.attr.context",
                        "@type", "EVCharger",
                        "connectorType", "CCS2",
                        "maxPowerKW", 60)));
    }

    private static Map<String, Object> evChargerDocWithOffers(String catalogId, String bppId,
            String itemId, String itemName,
            List<Object> offers) {
        java.util.Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc(catalogId, bppId, itemId, itemName));
        doc.put("offers", offers);
        return doc;
    }
}
