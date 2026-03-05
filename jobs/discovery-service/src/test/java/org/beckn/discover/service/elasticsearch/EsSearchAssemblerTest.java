package org.beckn.discover.service.elasticsearch;

import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Item;
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
        assertThat(catalog.getItems()).hasSize(1);

        Item item = catalog.getItems().get(0);
        assertThat(item.getId()).isEqualTo("item-1");
        assertThat(item.getDescriptor().getName()).isEqualTo("DC Fast Charger");
        assertThat(item.getDescriptor().getShortDesc()).isEqualTo("60kW CCS2 charger");
    }

    @Test
    void multipleHitsSameCatalog_groupedIntoOneCatalogWithManyItems() {
        List<Map<String, Object>> docs = List.of(
                evChargerDoc("cat-1", "bpp-1", "item-1", "DC Fast Charger CCS2"),
                evChargerDoc("cat-1", "bpp-1", "item-2", "AC Charger Type2")
        );

        List<Catalog> catalogs = assembler.assemble(docs, "tx-2");

        assertThat(catalogs).hasSize(1);
        assertThat(catalogs.get(0).getItems()).hasSize(2);
        assertThat(catalogs.get(0).getItems())
                .extracting(Item::getId)
                .containsExactlyInAnyOrder("item-1", "item-2");
    }

    @Test
    void hitsDifferentCatalogs_produceSeparateCatalogObjects() {
        List<Map<String, Object>> docs = List.of(
                evChargerDoc("cat-1", "bpp-1", "item-1", "CCS2 Charger"),
                evChargerDoc("cat-2", "bpp-2", "item-3", "Solar Panel")
        );

        List<Catalog> catalogs = assembler.assemble(docs, "tx-3");

        assertThat(catalogs).hasSize(2);
        assertThat(catalogs).extracting(Catalog::getId)
                .containsExactlyInAnyOrder("cat-1", "cat-2");
    }

    @Test
    void hitWithCategory_populatesCategoryOnItem() {
        Map<String, Object> doc = evChargerDoc("cat-1", "bpp-1", "item-1", "Charger");

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-4");

        Item item = catalogs.get(0).getItems().get(0);
        assertThat(item.getCategory()).isNotNull();
        assertThat(item.getCategory().getCodeValue()).isEqualTo("EV_CHARGING");
        assertThat(item.getCategory().getName()).isEqualTo("EV Charging");
    }

    @Test
    void hitWithRating_populatesRatingOnItem() {
        Map<String, Object> doc = evChargerDoc("cat-1", "bpp-1", "item-1", "Charger");

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-5");

        Item item = catalogs.get(0).getItems().get(0);
        assertThat(item.getRating()).isNotNull();
        assertThat(item.getRating().getRatingValue()).isEqualTo(4.5);
        assertThat(item.getRating().getRatingCount()).isEqualTo(120);
    }

    @Test
    void hitWithProvider_populatesProviderOnItem() {
        Map<String, Object> doc = evChargerDoc("cat-1", "bpp-1", "item-1", "Charger");

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-6");

        Item item = catalogs.get(0).getItems().get(0);
        assertThat(item.getProvider()).isNotNull();
        assertThat(item.getProvider().getId()).isEqualTo("ecopower-charging");
        assertThat(item.getProvider().getDescriptor().getName()).isEqualTo("EcoPower Charging");
    }

    @Test
    void hitWithItemAttributes_populatesAttributes() {
        Map<String, Object> doc = evChargerDoc("cat-1", "bpp-1", "item-1", "Charger");

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-7");

        Item item = catalogs.get(0).getItems().get(0);
        assertThat(item.getItemAttributes()).isNotNull();
        assertThat(item.getItemAttributes().getAttribute("connectorType")).isEqualTo("CCS2");
    }

    @Test
    void hitMissingCatalogId_isSkipped() {
        Map<String, Object> doc = evChargerDoc(null, "bpp-1", "item-1", "Charger");

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-8");

        assertThat(catalogs).isEmpty();
    }

    @Test
    void hitWithOffers_offersSetOnCatalog() {
        Map<String, Object> offer = Map.of("offer_id", "offer-1", "price_value", 150.0);
        Map<String, Object> doc = evChargerDocWithOffers("cat-1", "bpp-1", "item-1", "Charger", List.of(offer));

        List<Catalog> catalogs = assembler.assemble(List.of(doc), "tx-9");

        assertThat(catalogs.get(0).getOffers()).isNotNull().hasSize(1);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static Map<String, Object> evChargerDoc(String catalogId, String bppId,
                                                     String itemId, String itemName) {
        return Map.ofEntries(
                Map.entry("catalog_id",          catalogId != null ? catalogId : ""),
                Map.entry("bpp_id",              bppId),
                Map.entry("bpp_uri",             "https://bpp.example.com"),
                Map.entry("network_id",          "ondc-ev"),
                Map.entry("item_id",             itemId),
                Map.entry("item_name",           itemName),
                Map.entry("item_short_desc",     "60kW CCS2 charger"),
                Map.entry("item_long_desc",      "A fast DC charger supporting CCS2 connectors"),
                Map.entry("item_category_code",  "EV_CHARGING"),
                Map.entry("item_category_name",  "EV Charging"),
                Map.entry("item_rateable",       true),
                Map.entry("item_is_active",      true),
                Map.entry("item_rating_value",   4.5),
                Map.entry("item_rating_count",   120),
                Map.entry("item_provider_id",    "ecopower-charging"),
                Map.entry("item_provider_name",  "EcoPower Charging"),
                Map.entry("item_attributes",     Map.of("connectorType", "CCS2", "maxPowerKW", 60))
        );
    }

    private static Map<String, Object> evChargerDocWithOffers(String catalogId, String bppId,
                                                               String itemId, String itemName,
                                                               List<Object> offers) {
        java.util.Map<String, Object> doc = new java.util.HashMap<>(evChargerDoc(catalogId, bppId, itemId, itemName));
        doc.put("offers", offers);
        return doc;
    }
}
