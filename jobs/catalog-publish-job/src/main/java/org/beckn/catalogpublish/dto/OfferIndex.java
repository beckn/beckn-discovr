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
 * O(1) per-resource offer lookup; built once per catalog.
 *
 * <p>2-way classification:
 * <ul>
 *   <li>{@code offersByItemId} — offers with {@code resourceIds} (keyed by resource ID)</li>
 *   <li>{@code providerOffers} — offers without {@code resourceIds} (provider-level)</li>
 * </ul>
 */
public record OfferIndex(
    List<JsonNode> providerOffers,
    Map<String, List<JsonNode>> offersByItemId
) {
    public static OfferIndex build(JsonNode allOffers, ObjectMapper objectMapper) {
        if (allOffers == null || !allOffers.isArray() || allOffers.isEmpty()) {
            return new OfferIndex(List.of(), Map.of());
        }
        List<JsonNode> providerLevel = new ArrayList<>();
        Map<String, List<JsonNode>> byItemId = new HashMap<>();
        for (JsonNode offer : allOffers) {
            JsonNode resourceIdsNode = offer.path(BecknFields.RESOURCE_IDS);
            if (resourceIdsNode.isMissingNode() || !resourceIdsNode.isArray() || resourceIdsNode.isEmpty()) {
                providerLevel.add(offer);
            } else {
                for (JsonNode idNode : resourceIdsNode) {
                    byItemId.computeIfAbsent(idNode.asText(), k -> new ArrayList<>()).add(offer);
                }
            }
        }
        return new OfferIndex(
                Collections.unmodifiableList(providerLevel),
                Collections.unmodifiableMap(byItemId));
    }

    /**
     * Returns ONLY resource-specific offers for the given resource ID.
     * Provider-level offers are NOT included — they are resolved at search time.
     */
    public ArrayNode getOffersForResource(String itemId, ObjectMapper mapper) {
        ArrayNode result = mapper.createArrayNode();
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
