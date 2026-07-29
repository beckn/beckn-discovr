package org.beckn.catalogpublish.service.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.dto.OfferIndex;
import org.springframework.stereotype.Service;

import java.util.List;
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
        // bppId/bppUri are preserved verbatim so they can be echoed back at the catalog level
        // on discover responses; auth/ownership is still tracked via subscriberId, not these fields.
        ObjectNode slice = objectMapper.createObjectNode();
        catalogNode.fields().forEachRemaining(e -> {
            String key = e.getKey();
            if (!"items".equals(key) && !BecknFields.RESOURCES.equals(key)
                    && !BecknFields.OFFERS.equals(key))
                slice.set(key, e.getValue());
        });
        return slice;
    }

    public JsonNode buildDenormalizedPayloadFromSlice(
            ObjectNode baseSlice, JsonNode itemNode, OfferIndex offerIndex, String itemId) {
        ArrayNode itemOffers = offerIndex.getOffersForResource(itemId, objectMapper);
        ObjectNode itemSlice = baseSlice.deepCopy();
        itemSlice.set(BecknFields.RESOURCES, wrapInArray(itemNode));
        itemSlice.set(BecknFields.OFFERS, itemOffers);
        return objectMapper.createObjectNode().set(BecknFields.CATALOGS, wrapInArray(itemSlice));
    }

    /**
     * True when the catalog-level metadata stored in {@code storedPayload} differs from the
     * incoming {@code baseSlice}.
     *
     * <p>Catalog properties (descriptor, provider, validity, …) have no table of their own —
     * they are denormalized into every item row. A change therefore has to be propagated to
     * resources absent from the publish, and this is the cheap check that decides whether any
     * propagation is needed at all.
     *
     * <p>Compares the whole metadata object rather than field-by-field, so a field the
     * publisher dropped counts as a difference just like a changed one. Excludes exactly what
     * {@link #buildCatalogMetadataSlice} excludes, so the two stay symmetric.
     *
     * <p>Returns false for a payload with no {@code catalogs[0]} object — nothing reliable to
     * compare against, so no churn.
     */
    public boolean catalogMetadataDiffers(JsonNode storedPayload, ObjectNode baseSlice) {
        JsonNode stored = storedPayload.path(BecknFields.CATALOGS).path(0);
        if (!(stored instanceof ObjectNode storedCatalog)) return false;
        ObjectNode storedMeta = storedCatalog.deepCopy();
        storedMeta.remove(List.of("items", BecknFields.RESOURCES, BecknFields.OFFERS));
        return !storedMeta.equals(baseSlice);
    }

    /**
     * Rebuilds a stored payload with fresh catalog metadata, keeping its own resource and
     * offers untouched. Used to propagate a catalog-property change to a resource the publish
     * did not list.
     *
     * <p>Returns {@code null} when the stored payload carries no resource — rewriting such a
     * row would replace its resource with an empty array, so the caller skips it instead.
     */
    public JsonNode applyCatalogMetadata(JsonNode storedPayload, ObjectNode baseSlice) {
        JsonNode storedCatalog = storedPayload.path(BecknFields.CATALOGS).path(0);
        JsonNode storedResources = storedCatalog.path(BecknFields.RESOURCES);
        if (!storedResources.isArray() || storedResources.isEmpty()) return null;

        ObjectNode slice = baseSlice.deepCopy();
        slice.set(BecknFields.RESOURCES, storedResources);
        JsonNode storedOffers = storedCatalog.path(BecknFields.OFFERS);
        slice.set(BecknFields.OFFERS, storedOffers.isArray() ? storedOffers : objectMapper.createArrayNode());
        return objectMapper.createObjectNode().set(BecknFields.CATALOGS, wrapInArray(slice));
    }

    private ArrayNode wrapInArray(JsonNode node) {
        ArrayNode arr = objectMapper.createArrayNode();
        arr.add(node);
        return arr;
    }
}
