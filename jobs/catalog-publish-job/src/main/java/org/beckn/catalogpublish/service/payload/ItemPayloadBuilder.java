package org.beckn.catalogpublish.service.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.dto.OfferIndex;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.stream.StreamSupport;

@Service
public class ItemPayloadBuilder {

    private final ObjectMapper objectMapper;

    public ItemPayloadBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String[] extractOfferIdsFromPayload(JsonNode payload) {
        JsonNode offers = payload.path("catalogs").path(0).path("offers");
        if (!offers.isArray() || offers.isEmpty()) return new String[0];
        return StreamSupport.stream(offers.spliterator(), false)
                .map(o -> o.path("id").asText(null))
                .filter(Objects::nonNull)
                .toArray(String[]::new);
    }

    public ObjectNode buildCatalogMetadataSlice(JsonNode catalogNode, CatalogContext ctx) {
        // Copy only non-item/offer fields — avoids deep-copying all items/offers just to discard them.
        // buildDenormalizedPayloadFromSlice deep-copies this slice per item, so shallow refs are safe here.
        ObjectNode slice = objectMapper.createObjectNode();
        catalogNode.fields().forEachRemaining(e -> {
            if (!"items".equals(e.getKey()) && !"offers".equals(e.getKey()))
                slice.set(e.getKey(), e.getValue());
        });
        if (!slice.has("bppId")) slice.put("bppId", ctx.bppId());
        if (!slice.has("bppUri")) slice.put("bppUri", ctx.bppUri());
        return slice;
    }

    public JsonNode buildDenormalizedPayloadFromSlice(
            ObjectNode baseSlice, JsonNode itemNode, OfferIndex offerIndex, String itemId) {
        ArrayNode itemOffers = offerIndex.getOffersForItem(itemId, objectMapper);
        ObjectNode itemSlice = baseSlice.deepCopy();
        itemSlice.set("items", wrapInArray(itemNode));
        itemSlice.set("offers", itemOffers);
        return objectMapper.createObjectNode().set("catalogs", wrapInArray(itemSlice));
    }

    private ArrayNode wrapInArray(JsonNode node) {
        ArrayNode arr = objectMapper.createArrayNode();
        arr.add(node);
        return arr;
    }
}
