package org.beckn.catalogpublish.service.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.dto.CatalogBatch;
import org.beckn.catalogpublish.exception.CatalogPublishException;
import org.beckn.catalogpublish.model.Item;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Assembles the catalog-level Kafka message from a persisted {@link
 * CatalogBatch}.
 *
 * <p>Each batch covers one catalog. Saved items carry pre-parsed {@link
 * JsonNode} payloads
 * (via {@link CatalogBatch#payloadNodes()}) to avoid re-parsing JSON strings on
 * the
 * post-commit event thread.
 *
 * <p>Output envelope matches the inbound publish format so downstream consumers
 * can
 * process it with the same schema:
 * <pre>{@code
 * {
 * "context": { "bppId": "...", "bppUri": "..." },
 * "message": {
 * "catalogs": [
 * {
 * "id": "cat-1",
 * "bppId": "...",
 * "resources": [ ...all merged item nodes... ],
 * "offers": [ ...all unique offers across items... ]
 * }
 * ]
 * }
 * }
 * }</pre>
 */
@Service
public class CatalogPublishPayloadAssembler {

    private final ObjectMapper objectMapper;

    public CatalogPublishPayloadAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Builds and serializes the catalog-level Kafka message.
     *
     * <p>
     * Items are grouped by {@code catalogId} (preserving declaration order via
     * {@link LinkedHashMap}). Within each group, item nodes and offers are
     * extracted
     * directly from the pre-parsed {@link CatalogBatch#payloadNodes()} — no JSON
     * re-parsing occurs. Offers are deduplicated by {@code id}; first-seen
     * wins,
     * which correctly handles the case where the same offer appears in multiple
     * items'
     * denormalized payloads.
     *
     * @throws CatalogPublishException if serialization fails
     */
    public String assembleMessage(CatalogBatch batch) {
        return assembleMessage(batch, null);
    }

    /**
     * Builds and serializes the catalog-level Kafka message including only the
     * items whose IDs are in {@code itemIdsToInclude}.
     *
     * <p>When {@code itemIdsToInclude} is null or empty, no items are included
     * (resulting in an empty catalogs array or empty items list depending on
     * batch content). When non-null and non-empty, only items whose
     * {@link Item#getId()} is in the set are included. Used by the router to
     * publish a catalog message per target root containing only items that
     * resolve to that root.
     *
     * @param batch             the persisted catalog batch
     * @param itemIdsToInclude  optional set of item IDs to include; null means
     *                          include all (same as {@link #assembleMessage(CatalogBatch)})
     * @return assembled JSON string
     * @throws CatalogPublishException if serialization fails
     */
    public String assembleMessage(CatalogBatch batch, Set<String> itemIdsToInclude) {
        try {
            Map<String, JsonNode> payloadNodes = batch.payloadNodes();

            // When filtering, only include saved items whose id is in the set.
            List<Item> itemsToAssemble = itemIdsToInclude == null
                    ? batch.savedItems()
                    : batch.savedItems().stream()
                            .filter(i -> itemIdsToInclude.contains(i.getId()))
                            .toList();

            // Group by catalogId; within one batch all items are typically from the same
            // catalog, but grouping ensures correctness for any future multi-catalog
            // batches.
            // LinkedHashMap preserves declaration order so the assembled message lists
            // items in the same sequence they were persisted.
            Map<String, List<Item>> byCatalog = itemsToAssemble.stream()
                    .collect(Collectors.groupingBy(Item::getCatalogId,
                            LinkedHashMap::new, Collectors.toList()));

            ArrayNode catalogsArray = objectMapper.createArrayNode();
            byCatalog.values().forEach(group -> catalogsArray.add(buildCatalogNode(group, payloadNodes)));

            ObjectNode message = objectMapper.createObjectNode();
            message.set(BecknFields.CATALOGS, catalogsArray);

            // Forward the original inbound context node unchanged — all fields
            // (message_id, transaction_id, network_id, etc.) are preserved.
            ObjectNode root = objectMapper.createObjectNode();
            root.set(BecknFields.CONTEXT, batch.context().contextNode());
            root.set(BecknFields.MESSAGE, message);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new CatalogPublishException(
                    "Failed to assemble catalog message for catalogId=" + batch.catalogId(), e);
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private ObjectNode buildCatalogNode(List<Item> items, Map<String, JsonNode> payloadNodes) {
        // Take catalog metadata from the first item (all items in the same catalog
        // share it).
        JsonNode firstPayload = payloadNodes.get(items.get(0).getId());
        // Guard: if the first item's payload node is missing (e.g. payload-only
        // propagation path
        // did not populate payloadNodes), fall back to an empty node so the loop below
        // still runs.
        if (firstPayload == null || firstPayload.isMissingNode()) {
            firstPayload = objectMapper.createObjectNode();
        }
        JsonNode catalogTemplate = firstPayload.path(BecknFields.CATALOGS).path(0);

        // Copy catalog-level fields; items and offers are assembled below.
        ObjectNode catalogNode = objectMapper.createObjectNode();
        catalogTemplate.fields().forEachRemaining(entry -> {
            if (!BecknFields.ITEMS.equals(entry.getKey()) && !BecknFields.RESOURCES.equals(entry.getKey())
                    && !BecknFields.OFFERS.equals(entry.getKey())) {
                catalogNode.set(entry.getKey(), entry.getValue());
            }
        });

        // Single pass over items: extract item node and offers from each payload once.
        ArrayNode itemsArray = objectMapper.createArrayNode();
        Map<String, JsonNode> offerById = new HashMap<>();
        for (Item item : items) {
            JsonNode node = payloadNodes.get(item.getId());
            if (node == null)
                continue;
            JsonNode payloadCatalog = node.path(BecknFields.CATALOGS).path(0);
            JsonNode resourcesNode = payloadCatalog.path(BecknFields.RESOURCES);
            JsonNode itemNode = resourcesNode.path(0);
            if (!itemNode.isMissingNode()) {
                itemsArray.add(itemNode);
            }
            JsonNode offers = payloadCatalog.path(BecknFields.OFFERS);
            if (offers.isArray()) {
                for (JsonNode offer : offers) {
                    String offerId = offer.path(BecknFields.ID).asText(null);
                    if (offerId != null) {
                        offerById.putIfAbsent(offerId, offer);
                    }
                }
            }
        }

        ArrayNode offersArray = objectMapper.createArrayNode();
        offerById.values().forEach(offersArray::add);

        catalogNode.set(BecknFields.RESOURCES, itemsArray);
        catalogNode.set(BecknFields.OFFERS, offersArray);
        return catalogNode;
    }
}
