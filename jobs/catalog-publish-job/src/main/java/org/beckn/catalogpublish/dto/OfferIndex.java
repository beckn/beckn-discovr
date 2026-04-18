package org.beckn.catalogpublish.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.catalogpublish.common.BecknFields;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
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
            // Offers reference resources via "resourceIds"
            JsonNode offerItems = offer.path(BecknFields.RESOURCE_IDS);
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
        catalogWideOffers.forEach(o -> result.add(stripNulls(o, mapper)));
        offersByItemId.getOrDefault(itemId, List.of()).forEach(o -> result.add(stripNulls(o, mapper)));
        return result;
    }

    /**
     * Returns a copy of {@code offer} with all null-valued fields removed (RFC 7396 semantics).
     * A {@code null} field in an incoming offer means "delete this field" — it must not be
     * stored as a literal null in the persisted payload.
     * Arrays are left intact; only object fields are inspected.
     */
    private static JsonNode stripNulls(JsonNode offer, ObjectMapper mapper) {
        if (!offer.isObject()) return offer;
        boolean hasNulls = false;
        Iterator<JsonNode> vals = offer.elements();
        while (vals.hasNext()) {
            if (vals.next().isNull()) { hasNulls = true; break; }
        }
        if (!hasNulls) return offer; // fast path — no copy needed
        ObjectNode copy = offer.deepCopy();
        Iterator<Map.Entry<String, JsonNode>> fields = copy.fields();
        while (fields.hasNext()) {
            if (fields.next().getValue().isNull()) fields.remove();
        }
        return copy;
    }
}
