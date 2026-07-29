package org.beckn.catalogpublish.service.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.fge.jsonpatch.mergepatch.JsonMergePatch;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.exception.PayloadMergeException;
import org.beckn.catalogpublish.util.DenormalizedPayloadUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

@Service
public class PayloadMergeService {

    private final ObjectMapper objectMapper;

    public PayloadMergeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode mergeResourcePayload(String denormPayloadJson, JsonNode resourcePatch) {
        try {
            JsonNode denorm = parseOrEmpty(denormPayloadJson);
            JsonNode resourceNode = DenormalizedPayloadUtils.getFirstResourceNode(denorm);
            if (resourceNode == null || resourceNode.isMissingNode())
                return denorm;
            if (resourcePatch == null || resourcePatch.isNull())
                return rewrapResourceInDenormalized(denorm, resourceNode);
            JsonNode merged = JsonMergePatch.fromJson(resourcePatch).apply(resourceNode);
            return rewrapResourceInDenormalized(denorm, merged);
        } catch (Exception e) {
            throw new PayloadMergeException("Failed to merge resource payload: " + e.getMessage(), e);
        }
    }

    /**
     * Builds an offer-ID → array-index map for the offers already present in
     * {@code payload}.
     * Pass this into
     * {@link #mergeOfferIntoPayload(JsonNode, JsonNode, String, Map)} when
     * merging multiple offers into the same payload to avoid O(n) scans on every
     * call.
     * The map is kept up-to-date by the indexed overload (new offers are appended
     * and tracked).
     */
    public Map<String, Integer> buildOfferIndex(JsonNode payload) {
        ArrayNode offers = getOffersArray(payload);
        Map<String, Integer> index = new HashMap<>(Math.max(offers.size() * 2, 4));
        for (int i = 0; i < offers.size(); i++) {
            String id = offers.get(i).path(BecknFields.ID).asText(null);
            if (id != null)
                index.put(id, i);
        }
        return index;
    }

    /**
     * Indexed variant — O(1) offer lookup via the pre-built {@code offerIndex}.
     * The map is mutated to track any newly appended offers so it stays valid
     * across repeated calls on the same payload.
     *
     * <p>
     * <b>Mutates {@code payload} in-place</b> and returns the same reference.
     */
    public JsonNode mergeOfferIntoPayload(JsonNode payload, JsonNode patchOffer,
            String offerId, Map<String, Integer> offerIndex) {
        try {
            ArrayNode offers = getOffersArray(payload);
            Integer idx = offerIndex.get(offerId);
            if (idx == null) {
                offerIndex.put(offerId, offers.size()); // track the newly appended offer
                offers.add(patchOffer);
            } else {
                offers.set(idx, JsonMergePatch.fromJson(patchOffer).apply(offers.get(idx)));
            }
            return payload;
        } catch (Exception e) {
            throw new PayloadMergeException("Failed to merge offer into payload: " + e.getMessage(), e);
        }
    }

    /**
     * MERGE-mode offer preservation. Carries offers from the stored payload forward into a
     * freshly built one when the incoming publish never mentioned them.
     *
     * <p>A freshly built denormalized payload contains only the offers the incoming publish
     * attached to this resource, so a publisher updating just a resource (no {@code offers}
     * array at all) would otherwise wipe every offer the resource already had — and since
     * discover drops resources that end up with no offers, that silently delists them.
     *
     * <p>The decision hinges on {@code restatedOfferIds} — every offer id appearing anywhere
     * in this publish, not merely those attached to this resource:
     * <ul>
     * <li><b>Offer id absent from the publish</b> — the publisher was not talking about
     * offers; carry the stored offer forward.</li>
     * <li><b>Offer id present in the publish</b> — its {@code resourceIds} declare the
     * complete, current resource list. Not carried forward, so an offer restated without
     * this resource is correctly detached. Preserving it here would make offers impossible
     * to unlink in MERGE mode.</li>
     * </ul>
     *
     * <p>Offers already present in {@code payload} are never duplicated. Carried-forward
     * nodes come from a tree parsed here, so no incoming node is aliased or mutated.
     *
     * <p><b>Mutates {@code payload} in-place</b> and returns the same reference.
     * MERGE-only — FULL replace is authoritative and must keep clearing omitted offers.
     */
    public JsonNode carryForwardUnrestatedOffers(JsonNode payload, String storedPayloadJson,
            Set<String> restatedOfferIds) {
        JsonNode storedOffers = parseOrEmpty(storedPayloadJson)
                .path(BecknFields.CATALOGS).path(0).path(BecknFields.OFFERS);
        if (!storedOffers.isArray() || storedOffers.isEmpty())
            return payload; // nothing stored to carry — leave payload untouched

        Map<String, Integer> index = buildOfferIndex(payload);
        ArrayNode offers = getOffersArray(payload);
        for (JsonNode stored : storedOffers) {
            String id = stored.path(BecknFields.ID).asText(null);
            if (id == null || restatedOfferIds.contains(id) || index.containsKey(id))
                continue;
            index.put(id, offers.size());
            offers.add(stored);
        }
        return payload;
    }

    /**
     * Recursively removes all null-valued fields from an object node.
     * Arrays are left intact — array elements are not inspected.
     * <p>
     * Used by the upsert publish path so that a {@code null} in the incoming
     * payload means
     * "no change" rather than RFC 7396 "delete this field."
     * <p>
     * <b>Fast path:</b> if the node contains no nulls at any depth, the original
     * node is
     * returned as-is — no copy is made. A deep copy is only performed when nulls
     * are
     * actually present, keeping the common full-publish case allocation-free.
     */
    public JsonNode stripNulls(JsonNode node) {
        if (node == null || !node.isObject())
            return node;
        if (!containsNulls(node))
            return node; // fast path — nothing to remove
        ObjectNode copy = node.deepCopy();
        removeNullsInPlace(copy);
        return copy;
    }

    private static boolean containsNulls(JsonNode node) {
        Iterator<JsonNode> it = node.elements();
        while (it.hasNext()) {
            JsonNode v = it.next();
            if (v.isNull() || (v.isObject() && containsNulls(v)))
                return true;
        }
        return false;
    }

    private static void removeNullsInPlace(ObjectNode node) {
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue().isNull()) {
                it.remove();
            } else if (e.getValue().isObject()) {
                removeNullsInPlace((ObjectNode) e.getValue());
            }
        }
    }

    public JsonNode parseOrEmpty(String json) {
        try {
            if (json == null || json.isBlank())
                return objectMapper.createObjectNode();
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode rewrapResourceInDenormalized(JsonNode denorm, JsonNode mergedResource) {
        // denorm is freshly parsed from the DB string inside mergeResourcePayload — we own it.
        // Mutate the resources array in-place; no deep copy of the catalog wrapper needed.
        JsonNode catalogsNode = denorm.path("catalogs");
        if (catalogsNode.isArray() && !catalogsNode.isEmpty()) {
            ObjectNode catalog = (ObjectNode) catalogsNode.get(0);
            catalog.set(BecknFields.RESOURCES, objectMapper.createArrayNode().add(mergedResource));
            return denorm;
        }
        // Fallback: malformed denorm — build minimal structure from scratch.
        ObjectNode catalog = objectMapper.createObjectNode();
        catalog.set(BecknFields.RESOURCES, objectMapper.createArrayNode().add(mergedResource));
        return objectMapper.createObjectNode()
                .set("catalogs", objectMapper.createArrayNode().add(catalog));
    }

    private ArrayNode getOffersArray(JsonNode payload) {
        JsonNode cat = payload.path(BecknFields.CATALOGS).path(0);
        // path(0) returns MissingNode (not ObjectNode) when catalogs is absent or empty.
        // Casting MissingNode → ObjectNode throws ClassCastException; guard explicitly.
        if (cat.isMissingNode() || !(cat instanceof ObjectNode catObj)) {
            throw new IllegalStateException("payload missing catalogs[0] — cannot merge offer");
        }
        JsonNode offers = catObj.path(BecknFields.OFFERS);
        if (!offers.isArray()) {
            ArrayNode arr = objectMapper.createArrayNode();
            catObj.set(BecknFields.OFFERS, arr);
            return arr;
        }
        return (ArrayNode) offers;
    }

}
