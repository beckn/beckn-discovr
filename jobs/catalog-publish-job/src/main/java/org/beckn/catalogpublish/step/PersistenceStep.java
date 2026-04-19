package org.beckn.catalogpublish.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.catalogpublish.dto.CatalogBatch;
import org.beckn.catalogpublish.dto.CatalogContext;
import org.beckn.catalogpublish.dto.CatalogOperation;
import org.beckn.catalogpublish.dto.OfferIndex;
import org.beckn.catalogpublish.dto.ProcessingError;
import org.beckn.catalogpublish.dto.ProcessingErrorCode;
import org.beckn.catalogpublish.exception.FieldExtractionException;
import org.beckn.catalogpublish.model.Item;
import org.beckn.catalogpublish.model.ItemLocationCollection;
import org.beckn.catalogpublish.model.ProviderOffer;
import org.beckn.catalogpublish.service.geometry.GeometryExtractor;
import org.beckn.catalogpublish.service.payload.ItemPayloadBuilder;
import org.beckn.catalogpublish.service.payload.PayloadMergeService;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.store.ItemLocationCollectionStore;
import org.beckn.catalogpublish.store.ItemStore;
import org.beckn.catalogpublish.store.ProviderOfferStore;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.metrics.CatalogPublishMetrics;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.beckn.catalogpublish.util.FieldExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PersistenceStep {

    private static final Logger log = LoggerFactory.getLogger(PersistenceStep.class);

    private final ItemStore itemStore;
    private final ItemLocationCollectionStore locationStore;
    private final ProviderOfferStore providerOfferStore;
    private final ItemPayloadBuilder payloadBuilder;
    private final PayloadMergeService mergeService;
    private final GeometryExtractor geometryExtractor;
    private final ObjectMapper objectMapper;
    private final OfferResolutionStep offerResolutionStep;
    private final CatalogPublishMetrics metrics;

    public PersistenceStep(ItemStore itemStore,
            ItemLocationCollectionStore locationStore,
            ProviderOfferStore providerOfferStore,
            ItemPayloadBuilder payloadBuilder,
            PayloadMergeService mergeService,
            GeometryExtractor geometryExtractor,
            ObjectMapper objectMapper,
            OfferResolutionStep offerResolutionStep,
            CatalogPublishMetrics metrics) {
        this.itemStore = itemStore;
        this.locationStore = locationStore;
        this.providerOfferStore = providerOfferStore;
        this.payloadBuilder = payloadBuilder;
        this.mergeService = mergeService;
        this.geometryExtractor = geometryExtractor;
        this.objectMapper = objectMapper;
        this.offerResolutionStep = offerResolutionStep;
        this.metrics = metrics;
    }

    /**
     * Persists all items from a catalog node using an upsert strategy.
     *
     * @param catalogNode  the individual catalog node from {@code message.catalogs[i]}
     * @param ctx          parsed catalog context (network IDs, subscriber, etc.)
     * @param op           operation type (PUBLISH, etc.)
     * @param messageNode  the full {@code message} node — used to read message-level
     *                     {@code publishDirectives} array introduced in the directive-map pattern
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CatalogBatch persistItemsAndLocations(JsonNode catalogNode, CatalogContext ctx,
            CatalogOperation op, JsonNode messageNode) {
        String catalogId = FieldExtractor.requireString(catalogNode, "id");
        JsonNode allOffers = FieldExtractor.extractOffersOrEmpty(catalogNode);
        String schemaType = FieldExtractor.extractSchemaType(catalogNode, ctx.contextNode());

        // Read updateMode from message-level publishDirectives array (keyed by catalogId).
        var updateMode = extractUpdateMode(messageNode, catalogId);
        boolean isFullReplace = "FULL".equalsIgnoreCase(updateMode);

        if (isFullReplace) {
            // Delete locations BEFORE items — location subquery references item table
            int deletedLocations = locationStore.deleteByCatalogId(catalogId);
            int deletedItems = itemStore.deleteByCatalogId(catalogId);
            metrics.recordFullReplace(deletedItems, deletedLocations);
            log.info("event={} catalogId={} deletedItems={} deletedLocations={}",
                    LogEvent.FULL_REPLACE_DELETED, catalogId, deletedItems, deletedLocations);
        } else {
            metrics.recordMerge();
        }

        ObjectNode baseSlice = payloadBuilder.buildCatalogMetadataSlice(catalogNode, ctx);
        OfferIndex offerIndex = OfferIndex.build(allOffers, objectMapper);

        Map<String, JsonNode> incomingOfferById = buildIncomingOfferMap(allOffers);

        record IdAndNode(String itemId, JsonNode itemNode) {}
        List<IdAndNode> pairs = new ArrayList<>();
        List<ProcessingError> errors = new ArrayList<>();
        String catalogContextUrl = FieldExtractor.extractContextUrl(catalogNode);
        for (JsonNode itemNode : FieldExtractor.iterableItems(catalogNode)) {
            if (!FieldExtractor.isRealResource(itemNode)) continue;
            try {
                pairs.add(new IdAndNode(extractItemId(itemNode), itemNode));
            } catch (Exception e) {
                errors.add(new ProcessingError(null, ProcessingErrorCode.NET_INTERNAL_ERROR,
                        ErrorSanitizer.sanitize(e)));
            }
        }

        List<String> allItemIds = pairs.stream().map(IdAndNode::itemId).toList();
        Map<String, Item> existingById = allItemIds.isEmpty() ? Map.of()
                : itemStore.findAllByIdInAndCatalogId(allItemIds, catalogId).stream()
                        .collect(Collectors.toMap(Item::getId, Function.identity()));

        record ItemWithNode(Item item, JsonNode payloadNode) {}
        List<ItemWithNode> built = new ArrayList<>();

        // Phase 1: process explicitly listed items (new or upsert).
        for (IdAndNode pair : pairs) {
            String itemId = pair.itemId();
            JsonNode itemNode = pair.itemNode();
            try {
                // Catalg sends a fully resolved payload — always replace, never merge.
                JsonNode payload = payloadBuilder.buildDenormalizedPayloadFromSlice(baseSlice, itemNode, offerIndex, itemId);
                String[] offerIds = payloadBuilder.extractOfferIdsFromPayload(payload);
                String type = Optional.ofNullable(FieldExtractor.extractItemAttributesType(itemNode))
                        .orElse(FieldExtractor.extractItemType(itemNode));
                String attrsContextUrl = FieldExtractor.extractItemAttributesContextUrl(itemNode);
                String itemContextUrl = FieldExtractor.extractContextUrl(itemNode);
                String contextUrl = attrsContextUrl != null
                        ? attrsContextUrl
                        : (itemContextUrl != null ? itemContextUrl : catalogContextUrl);
                built.add(new ItemWithNode(
                        Item.from(itemId, payload.toString(), offerIds,
                                ctx.recordId(), ctx.subscriberId(), catalogId,
                                type, contextUrl, ctx.networkIds().toArray(new String[0])),
                        payload));
            } catch (Exception e) {
                String sanitized = ErrorSanitizer.sanitize(e);
                errors.add(new ProcessingError(itemId, ProcessingErrorCode.NET_INTERNAL_ERROR, sanitized));
                log.warn("event={} itemId={} catalogId={} error={}", LogEvent.PERSIST_FAILED, itemId, catalogId, sanitized);
            }
        }

        // Phase 2: offer propagation — push updated offers to items NOT in the explicit payload.
        if (!incomingOfferById.isEmpty()) {
            Set<String> explicitIds = new HashSet<>(allItemIds);
            List<Item> linkedItems = itemStore.findAllByCatalogIdAndAnyOfferId(
                    catalogId, new ArrayList<>(incomingOfferById.keySet()));

            for (Item linkedItem : linkedItems) {
                if (explicitIds.contains(linkedItem.getId())) continue;
                if (!catalogId.equals(linkedItem.getCatalogId())) continue;

                try {
                    JsonNode payload = mergeService.parseOrEmpty(linkedItem.getPayload());
                    boolean changed = false;
                    Map<String, Integer> payloadOfferIndex = null;
                    for (String linkedOfferId : linkedItem.getOfferIds()) {
                        JsonNode incomingOffer = incomingOfferById.get(linkedOfferId);
                        if (incomingOffer != null) {
                            if (payloadOfferIndex == null)
                                payloadOfferIndex = mergeService.buildOfferIndex(payload);
                            mergeService.mergeOfferIntoPayload(payload, incomingOffer, linkedOfferId, payloadOfferIndex);
                            changed = true;
                        }
                    }
                    if (changed) {
                        String[] offerIds = payloadBuilder.extractOfferIdsFromPayload(payload);
                        built.add(new ItemWithNode(
                                Item.from(linkedItem.getId(), payload.toString(), offerIds,
                                        linkedItem.getCreatedBy(), linkedItem.getSubscriberId(),
                                        linkedItem.getCatalogId(),
                                        linkedItem.getType(), linkedItem.getContextUrl(),
                                        linkedItem.getNetworkIds().toArray(new String[0])),
                                payload));
                        log.debug("event={} itemId={} offers={}", LogEvent.PERSIST_COMPLETED,
                                linkedItem.getId(), linkedItem.getOfferIds());
                    }
                } catch (Exception e) {
                    String sanitized = ErrorSanitizer.sanitize(e);
                    errors.add(new ProcessingError(linkedItem.getId(), ProcessingErrorCode.NET_INTERNAL_ERROR, sanitized));
                    log.warn("event={} itemId={} catalogId={} error={}",
                            LogEvent.PERSIST_FAILED, linkedItem.getId(), catalogId, sanitized);
                }
            }
        }

        // Phase 3: Cross-catalog offer resolution — attach offers to items owned by other catalogs.
        if (!incomingOfferById.isEmpty()) {
            // Phase 2 items must be in handledIds to prevent Phase 3 (cross-BPP offers) from
            // double-processing items that were already updated by Phase 2 (same-catalog offer propagation).
            Set<String> handledIds = new HashSet<>(allItemIds);
            built.forEach(iwn -> handledIds.add(iwn.item().getId()));

            var resolved = offerResolutionStep.resolveCrossBppOffers(incomingOfferById, handledIds, ctx);
            for (var r : resolved) {
                built.add(new ItemWithNode(r.item(), r.payloadNode()));
            }
        }

        // Phase 4: Persist provider-level offers (no resourceIds) to provider_offer table.
        // Runs BEFORE built.isEmpty() so offer-only catalogs still persist provider offers.
        persistProviderOffers(offerIndex, catalogId, catalogNode, ctx, isFullReplace);

        if (built.isEmpty()) {
            return new CatalogBatch(catalogId, ctx, schemaType, op, List.of(), List.copyOf(errors), Map.of(), isFullReplace);
        }

        Map<String, JsonNode> payloadNodeById = new HashMap<>();
        built.forEach(p -> payloadNodeById.put(p.item().getId(), p.payloadNode()));

        int insertCount = (int) built.stream()
                .filter(iwn -> !existingById.containsKey(iwn.item().getId()))
                .count();
        int updateCount = built.size() - insertCount;

        List<Item> savedItems = itemStore.saveAll(built.stream().map(ItemWithNode::item).toList());
        List<ItemLocationCollection> allLocations = savedItems.stream()
                .flatMap(item -> {
                    JsonNode node = payloadNodeById.get(item.getId());
                    return (node != null
                            ? geometryExtractor.extractLocations(item.getId(), item.getCatalogId(), node)
                            : geometryExtractor.extractLocations(item.getId(), item.getCatalogId(), item.getPayload())).stream();
                })
                .toList();
        locationStore.saveLocations(allLocations);

        metrics.recordPersistInserted(insertCount);
        metrics.recordPersistUpdated(updateCount);
        log.info("event={} catalogId={} mode={} items={} inserted={} updated={} locations={} errors={}",
                LogEvent.PERSIST_COMPLETED, catalogId, updateMode, savedItems.size(),
                insertCount, updateCount, allLocations.size(), errors.size());
        return new CatalogBatch(catalogId, ctx, schemaType, op,
                List.copyOf(savedItems), List.copyOf(errors), Map.copyOf(payloadNodeById), isFullReplace);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Reads {@code updateMode} from the message-level {@code publishDirectives} array by matching
     * on {@code catalogId}. Returns {@code "MERGE"} when no matching directive is found.
     *
     * <p>This is the directive-map pattern: {@code message.publishDirectives[]} is an array
     * of {@code { catalogId, catalogType, updateMode, ... }} objects, keyed by {@code catalogId}.
     */
    private String extractUpdateMode(JsonNode messageNode, String catalogId) {
        if (messageNode == null || messageNode.isMissingNode() || messageNode.isNull()) {
            return "MERGE";
        }
        var directives = messageNode.path(BecknFields.PUBLISH_DIRECTIVES);
        if (directives.isArray()) {
            for (var d : directives) {
                if (catalogId.equals(d.path("catalogId").asText(null))) {
                    return d.path(BecknFields.UPDATE_MODE).asText("MERGE");
                }
            }
        }
        return "MERGE";
    }

    private String extractItemId(JsonNode itemNode) {
        return FieldExtractor.extractString(itemNode, BecknFields.ID)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new FieldExtractionException("Item missing id"));
    }

    private Map<String, JsonNode> buildIncomingOfferMap(JsonNode allOffers) {
        if (allOffers == null || !allOffers.isArray() || allOffers.isEmpty())
            return Map.of();
        Map<String, JsonNode> map = new HashMap<>();
        for (JsonNode offer : allOffers) {
            String offerId = FieldExtractor.extractString(offer, BecknFields.ID).orElse(null);
            if (offerId != null && !offerId.isBlank())
                map.put(offerId, offer);
        }
        return map;
    }

    /**
     * Phase 4: Persists provider-level offers (offers without {@code resourceIds}) to
     * the {@code provider_offer} table. Provider ID is always extracted from
     * {@code catalog.provider.id}.
     *
     * <p>FULL mode: deletes all existing provider offers for this catalog first.
     * MERGE mode: upserts by (offer_id, catalog_id).</p>
     */
    private void persistProviderOffers(OfferIndex offerIndex, String catalogId,
            JsonNode catalogNode, CatalogContext ctx, boolean isFullReplace) {

        if (isFullReplace) {
            int deleted = providerOfferStore.deleteByCatalogId(catalogId);
            if (deleted > 0) {
                log.info("event={} catalogId={} deleted={}", LogEvent.PROVIDER_OFFER_DELETED, catalogId, deleted);
            }
        }

        List<JsonNode> providerOffers = offerIndex.providerOffers();
        if (providerOffers.isEmpty()) return;

        String providerId = extractProviderId(catalogNode);
        if (providerId == null || providerId.isBlank()) {
            log.warn("event={} catalogId={} reason=missing-provider-id",
                    LogEvent.PROVIDER_OFFER_SKIPPED, catalogId);
            return;
        }

        List<ProviderOffer> entities = new ArrayList<>();
        for (JsonNode offerNode : providerOffers) {
            String offerId = FieldExtractor.extractString(offerNode, BecknFields.ID).orElse(null);
            if (offerId == null || offerId.isBlank()) continue;
            try {
                String payload = objectMapper.writeValueAsString(offerNode);
                entities.add(ProviderOffer.from(offerId, catalogId, providerId,
                        payload, ctx.recordId(), ctx.subscriberId()));
            } catch (Exception e) {
                log.warn("event={} offerId={} catalogId={} error={}",
                        LogEvent.PERSIST_FAILED, offerId, catalogId, ErrorSanitizer.sanitize(e));
            }
        }

        if (!entities.isEmpty()) {
            providerOfferStore.saveAll(entities);
            log.info("event={} catalogId={} providerId={} count={}",
                    LogEvent.PROVIDER_OFFER_PERSISTED, catalogId, providerId, entities.size());
        }
    }

    private String extractProviderId(JsonNode catalogNode) {
        if (catalogNode == null) return null;
        JsonNode provider = catalogNode.path(BecknFields.PROVIDER);
        if (provider.isMissingNode() || provider.isNull()) return null;
        return FieldExtractor.extractString(provider, BecknFields.ID).orElse(null);
    }
}
