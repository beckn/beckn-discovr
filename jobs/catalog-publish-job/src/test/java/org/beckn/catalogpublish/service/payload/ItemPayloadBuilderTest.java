package org.beckn.catalogpublish.service.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.dto.OfferIndex;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ItemPayloadBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ItemPayloadBuilder builder = new ItemPayloadBuilder(mapper);

    @Test
    void buildCatalogMetadataSlice_removesItemsAndOffers() {
        ObjectNode catalog = mapper.createObjectNode();
        catalog.put("id", "c1");
        catalog.putArray("resources").add(mapper.createObjectNode());
        CatalogContext ctx = new CatalogContext("b1", "http://b1", new String[0], null);
        JsonNode slice = builder.buildCatalogMetadataSlice(catalog, ctx);
        assertThat(slice.has("resources")).isFalse();
        assertThat(slice.has("offers")).isFalse();
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
    void offerIndex_getOffersForItem_returnsCatalogWideOffers() {
        ArrayNode offers = mapper.createArrayNode();
        ObjectNode o = mapper.createObjectNode();
        o.put("id", "off1");
        offers.add(o);
        OfferIndex index = OfferIndex.build(offers, mapper);
        assertThat(index.getOffersForItem("any", mapper)).hasSize(1);
    }
}
