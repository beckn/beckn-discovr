package org.beckn.catalogpublish.service.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.dto.OfferIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ItemPayloadBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ItemPayloadBuilder builder = new ItemPayloadBuilder(mapper);

    @Test
    void buildCatalogMetadataSlice_removesItemsOffersAndBppFields() {
        ObjectNode catalog = mapper.createObjectNode();
        catalog.put("id", "c1");
        catalog.putArray("resources").add(mapper.createObjectNode());
        CatalogContext ctx = new CatalogContext(List.of(), null);
        JsonNode slice = builder.buildCatalogMetadataSlice(catalog, ctx);
        assertThat(slice.has("resources")).isFalse();
        assertThat(slice.has("offers")).isFalse();
        assertThat(slice.has("bppId")).isFalse();
        assertThat(slice.has("bppUri")).isFalse();
        assertThat(slice.path("id").asText()).isEqualTo("c1");
    }

    @Test
    void buildCatalogMetadataSlice_stripsBppIdAndBppUriFromCatalog() {
        // bppId/bppUri in the catalog body must be stripped — never stored per the schema redesign
        ObjectNode catalog = mapper.createObjectNode();
        catalog.put("id", "c1");
        catalog.put("bppId", "catalog-bpp");
        catalog.put("bppUri", "http://catalog-bpp");
        catalog.putArray("resources").add(mapper.createObjectNode());
        CatalogContext ctx = new CatalogContext(List.of(), null);
        JsonNode slice = builder.buildCatalogMetadataSlice(catalog, ctx);
        // bppId/bppUri must NOT appear in the stored payload
        assertThat(slice.has("bppId")).isFalse();
        assertThat(slice.has("bppUri")).isFalse();
        assertThat(slice.path("id").asText()).isEqualTo("c1");
    }

    @Test
    void extractOfferIdsFromPayload_emptyWhenNoOffers() {
        ObjectNode cat = mapper.createObjectNode();
        cat.putArray("offers");
        ArrayNode catalogs = mapper.createArrayNode();
        catalogs.add(cat);
        ObjectNode payload = mapper.createObjectNode();
        payload.set("catalogs", catalogs);
        String[] ids = builder.extractOfferIdsFromPayload(payload);
        assertThat(ids).isEmpty();
    }

    @Test
    void offerIndex_getOffersForResource_excludesProviderOffers() {
        // Offer without resourceIds is a provider-level offer — NOT returned by getOffersForResource
        ArrayNode offers = mapper.createArrayNode();
        ObjectNode provOffer = mapper.createObjectNode();
        provOffer.put("id", "prov-off1");
        offers.add(provOffer);
        OfferIndex index = OfferIndex.build(offers, mapper);
        assertThat(index.getOffersForResource("any", mapper)).isEmpty();
        assertThat(index.providerOffers()).hasSize(1);
    }

    @Test
    void offerIndex_getOffersForResource_returnsItemSpecificOffers() {
        // Offer with resourceIds is item-level — returned by getOffersForResource
        ArrayNode offers = mapper.createArrayNode();
        ObjectNode itemOffer = mapper.createObjectNode();
        itemOffer.put("id", "item-off1");
        itemOffer.set("resourceIds", mapper.createArrayNode().add("item-123"));
        offers.add(itemOffer);
        OfferIndex index = OfferIndex.build(offers, mapper);
        assertThat(index.getOffersForResource("item-123", mapper)).hasSize(1);
        assertThat(index.providerOffers()).isEmpty();
    }
}
