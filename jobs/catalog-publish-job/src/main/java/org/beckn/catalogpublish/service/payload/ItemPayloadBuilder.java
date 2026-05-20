package org.beckn.catalogpublish.service.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.catalogpublish.common.BecknFields;
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
        JsonNode offers = payload.path(BecknFields.CATALOGS).path(0).path(BecknFields.OFFERS);
        if (!offers.isArray() || offers.isEmpty()) return new String[0];
        return StreamSupport.stream(offers.spliterator(), false)
                .map(o -> o.path(BecknFields.ID).asText(null))
                .filter(Objects::nonNull)
                .toArray(String[]::new);
    }

    public ObjectNode buildCatalogMetadataSlice(JsonNode catalogNode, CatalogContext ctx) {
        // Copy only non-item/offer fields — avoids deep-copying all items/offers just to discard them.
        // buildDenormalizedPayloadFromSlice deep-copies this slice per item, so shallow refs are safe here.
        //
        // NOTE: bppId / bppUri are NOT injected from context. They come only from the
        // catalog object itself (via the field-copy loop above). When the catalog has
        // neither field, the denormalized payload simply omits them — downstream readers
        // (discover PostgreSQL assembler, ES doc assembler, pull API) must tolerate absence.
        // The {@code ctx} parameter is retained for other callers/fields but is no longer
        // used for bpp injection.
        ObjectNode slice = objectMapper.createObjectNode();
        catalogNode.fields().forEachRemaining(e -> {
            // Exclude items/resources (per-item data) and offers — added back per-item in buildDenormalizedPayloadFromSlice
            if (!BecknFields.ITEMS.equals(e.getKey()) && !BecknFields.RESOURCES.equals(e.getKey())
                    && !BecknFields.OFFERS.equals(e.getKey()))
                slice.set(e.getKey(), e.getValue());
        });
        return slice;
    }

    public JsonNode buildDenormalizedPayloadFromSlice(
            ObjectNode baseSlice, JsonNode itemNode, OfferIndex offerIndex, String itemId) {
        ArrayNode itemOffers = offerIndex.getOffersForItem(itemId, objectMapper);
        ObjectNode itemSlice = baseSlice.deepCopy();
        itemSlice.set(BecknFields.RESOURCES, wrapInArray(itemNode));
        itemSlice.set(BecknFields.OFFERS, itemOffers);
        return objectMapper.createObjectNode().set(BecknFields.CATALOGS, wrapInArray(itemSlice));
    }

    private ArrayNode wrapInArray(JsonNode node) {
        ArrayNode arr = objectMapper.createArrayNode();
        arr.add(node);
        return arr;
    }
}
