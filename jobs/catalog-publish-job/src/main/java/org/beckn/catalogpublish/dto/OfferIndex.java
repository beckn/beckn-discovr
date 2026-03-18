package org.beckn.catalogpublish.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.beckn.catalogpublish.common.BecknFields;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * O(1) per-item offer lookup; built once per catalog.
 */
public record OfferIndex(
    List<JsonNode> catalogWideOffers,
    Map<String, List<JsonNode>> offersByItemId
) {
    public static OfferIndex build(JsonNode allOffers, ObjectMapper objectMapper) {
        if (allOffers == null || !allOffers.isArray() || allOffers.isEmpty()) {
            return new OfferIndex(List.of(), Map.of());
        }
        List<JsonNode> catalogWide = new ArrayList<>();
        Map<String, List<JsonNode>> byItemId = new HashMap<>();
        for (JsonNode offer : allOffers) {
            JsonNode offerItems = offer.path(BecknFields.ITEMS);
            if (offerItems.isMissingNode() || !offerItems.isArray()) {
                catalogWide.add(offer);
            } else {
                for (JsonNode idNode : offerItems) {
                    byItemId.computeIfAbsent(idNode.asText(), k -> new ArrayList<>()).add(offer);
                }
            }
        }
        return new OfferIndex(
                Collections.unmodifiableList(catalogWide),
                Collections.unmodifiableMap(byItemId));
    }

    public ArrayNode getOffersForItem(String itemId, ObjectMapper mapper) {
        ArrayNode result = mapper.createArrayNode();
        catalogWideOffers.forEach(result::add);
        offersByItemId.getOrDefault(itemId, List.of()).forEach(result::add);
        return result;
    }
}
