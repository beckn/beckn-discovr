package org.beckn.catalogpublish.step;

import com.fasterxml.jackson.databind.JsonNode;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.metrics.CatalogPublishMetrics;
import org.beckn.catalogpublish.model.Item;
import org.beckn.catalogpublish.service.payload.ItemPayloadBuilder;
import org.beckn.catalogpublish.service.payload.PayloadMergeService;
import org.beckn.catalogpublish.store.ItemStore;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.beckn.catalogpublish.util.FieldExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 3 of the persistence pipeline: cross-catalog offer resolution.
 *
 * <p>When a BPP publishes offers whose {@code resourceIds} reference resources
 * owned by a different catalog, this step fetches those items from the DB (by ID only,
 * no catalog filter) and merges the applicable offers into their denormalised payloads
 * using RFC 7396 merge-patch — exactly as Phase 1/2 do for same-catalog items.
 *
 * <p>The updated item retains its original catalog identity ({@code catalog_id});
 * the publishing catalog's identity is never written onto another catalog's item.
 */
@Service
public class OfferResolutionStep {

    private static final Logger log = LoggerFactory.getLogger(OfferResolutionStep.class);

    public record ResolvedItem(Item item, JsonNode payloadNode) {}

    private final ItemStore itemStore;
    private final PayloadMergeService mergeService;
    private final ItemPayloadBuilder payloadBuilder;
    private final CatalogPublishMetrics metrics;

    public OfferResolutionStep(ItemStore itemStore,
            PayloadMergeService mergeService,
            ItemPayloadBuilder payloadBuilder,
            CatalogPublishMetrics metrics) {
        this.itemStore = itemStore;
        this.mergeService = mergeService;
        this.payloadBuilder = payloadBuilder;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<ResolvedItem> resolveCrossBppOffers(
            Map<String, JsonNode> incomingOfferById,
            Set<String> alreadyHandledIds,
            CatalogContext ctx) {

        if (incomingOfferById.isEmpty()) return List.of();

        // 1. Collect all resourceIds from all incoming offers
        Set<String> allResourceIds = new LinkedHashSet<>();
        for (var offer : incomingOfferById.values()) {
            JsonNode resourceIds = offer.path(BecknFields.RESOURCE_IDS);
            if (resourceIds.isArray()) {
                for (JsonNode idNode : resourceIds) {
                    String rid = idNode.asText(null);
                    if (rid != null && !rid.isBlank()) allResourceIds.add(rid);
                }
            }
        }

        // 2. Remove IDs already handled by Phase 1/2
        allResourceIds.removeAll(alreadyHandledIds);
        if (allResourceIds.isEmpty()) return List.of();

        // 3. Find existing items by resource IDs across all catalogs
        List<Item> existingItems = itemStore.findAllByIdIn(new ArrayList<>(allResourceIds));
        if (existingItems.isEmpty()) {
            log.warn("event={} resourceIdCount={}", LogEvent.OFFER_RESOLVE_SKIPPED, allResourceIds.size());
            metrics.recordOfferResolveMissing(allResourceIds.size());
            return List.of();
        }

        // 4. Report any IDs that were not found
        Set<String> foundIds = new LinkedHashSet<>();
        for (Item item : existingItems) foundIds.add(item.getId());
        Set<String> missingIds = new LinkedHashSet<>(allResourceIds);
        missingIds.removeAll(foundIds);
        if (!missingIds.isEmpty()) {
            log.warn("event={} missingResourceIdCount={}", LogEvent.OFFER_RESOLVE_SKIPPED, missingIds.size());
            metrics.recordOfferResolveMissing(missingIds.size());
        }

        // 5. For each found item, merge applicable offers into its payload
        var results = new ArrayList<ResolvedItem>();
        for (Item existingItem : existingItems) {
            try {
                JsonNode payload = mergeService.parseOrEmpty(existingItem.getPayload());
                boolean changed = false;
                Map<String, Integer> payloadOfferIndex = null;

                for (Map.Entry<String, JsonNode> entry : incomingOfferById.entrySet()) {
                    String offerId = entry.getKey();
                    JsonNode offer = entry.getValue();
                    JsonNode resourceIds = offer.path(BecknFields.RESOURCE_IDS);
                    if (!resourceIds.isArray()) continue;

                    boolean referencesThisItem = false;
                    for (JsonNode idNode : resourceIds) {
                        if (existingItem.getId().equals(idNode.asText(null))) {
                            referencesThisItem = true;
                            break;
                        }
                    }
                    if (!referencesThisItem) continue;

                    if (payloadOfferIndex == null) payloadOfferIndex = mergeService.buildOfferIndex(payload);
                    mergeService.mergeOfferIntoPayload(payload, offer, offerId, payloadOfferIndex);
                    changed = true;
                }

                if (!changed) continue;

                String[] newOfferIds = payloadBuilder.extractOfferIdsFromPayload(payload);

                // Merge new offer IDs with previously stored offer IDs — preserves history
                Set<String> mergedOfferIds = new LinkedHashSet<>();
                mergedOfferIds.addAll(existingItem.getOfferIds());
                if (newOfferIds != null) mergedOfferIds.addAll(Arrays.asList(newOfferIds));

                // Preserve the original item's catalog identity — never overwrite with the publishing catalog.
                Item updatedItem = Item.from(
                        existingItem.getId(),
                        payload.toString(),
                        mergedOfferIds.toArray(new String[0]),
                        existingItem.getCatalogId(),
                        existingItem.getType(),
                        existingItem.getContextUrl(),
                        existingItem.getNetworkIds().toArray(new String[0]));

                results.add(new ResolvedItem(updatedItem, payload));

                log.info("event={} itemId={} catalogId={} offersAttached={}",
                        LogEvent.OFFER_RESOLVE_COMPLETED,
                        existingItem.getId(),
                        existingItem.getCatalogId(),
                        newOfferIds != null ? newOfferIds.length : 0);
                metrics.recordOfferResolveSuccess();

            } catch (Exception e) {
                log.warn("event={} itemId={} error={}",
                        LogEvent.OFFER_RESOLVE_SKIPPED,
                        existingItem.getId(),
                        ErrorSanitizer.sanitize(e));
                metrics.recordOfferResolveFailed();
            }
        }

        return List.copyOf(results);
    }
}
