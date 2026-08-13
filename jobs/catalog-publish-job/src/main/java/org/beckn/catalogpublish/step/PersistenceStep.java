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

    /** An item about to be written, paired with the payload it was built from. */
    private record ResourceWithNode(Item item, JsonNode payloadNode) {}

    private final ItemStore itemStore;
    private final ItemLocationCollectionStore locationStore;
    private final ProviderOfferStore providerOfferStore;
    private final ItemPayloadBuilder payloadBuilder;
    private final PayloadMergeService mergeService;
    private final GeometryExtractor geometryExtractor;
    private final ObjectMapper objectMapper;
    private final OfferResolutionStep offerResolutionStep;
    private final CatalogMetadataPropagationStep metadataPropagationStep;
    private final CatalogPublishMetrics metrics;

    public PersistenceStep(ItemStore itemStore,
            ItemLocationCollectionStore locationStore,
            ProviderOfferStore providerOfferStore,
            ItemPayloadBuilder payloadBuilder,
            PayloadMergeService mergeService,
            GeometryExtractor geometryExtractor,
            ObjectMapper objectMapper,
            OfferResolutionStep offerResolutionStep,
            CatalogMetadataPropagationStep metadataPropagationStep,
            CatalogPublishMetrics metrics) {
        this.itemStore = itemStore;
        this.locationStore = locationStore;
        this.providerOfferStore = providerOfferStore;
        this.payloadBuilder = payloadBuilder;
        this.mergeService = mergeService;
        this.geometryExtractor = geometryExtractor;
        this.objectMapper = objectMapper;
        this.offerResolutionStep = offerResolutionStep;
        this.metadataPropagationStep = metadataPropagationStep;
        this.metrics = metrics;
    }

    /**
     * Persists all resources from a catalog node using an upsert strategy.
     *
     * @param catalogNode  the individual catalog node from {@code message.catalogs[i]}
     * @param ctx          parsed catalog context (network IDs, subscriber, etc.)
     * @param op           operation type (PUBLISH, etc.)
     * @param messageNode  the full {@code message} node — used to read message-level
     *                     {@code publishDirectives} array introduced in the directive-map pattern
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CatalogBatch persistResourcesAndLocations(JsonNode catalogNode, CatalogContext ctx,
            CatalogOperation op, JsonNode messageNode) {
        String catalogId = FieldExtractor.requireString(catalogNode, "id");
        JsonNode allOffers = FieldExtractor.extractOffersOrEmpty(catalogNode);
        String schemaType = FieldExtractor.extractSchemaType(catalogNode, ctx.contextNode());

        // Read updateMode from message-level publishDirectives array (keyed by catalogId).
        var updateMode = extractUpdateMode(messageNode, catalogId);
        boolean isFullReplace = "FULL".equalsIgnoreCase(updateMode);

        // Effective network IDs: prefer visibleTo from publishDirectives (multi-network),
        // falling back to context.networkId (single-network, pre-visibleTo compat).
        var visibleTo = extractVisibleTo(messageNode, catalogId);
        var effectiveNetworkIds = visibleTo.isEmpty() ? ctx.networkIds() : visibleTo;

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

        record IdAndNode(String resourceId, JsonNode resourceNode) {}
        List<IdAndNode> pairs = new ArrayList<>();
        List<ProcessingError> errors = new ArrayList<>();
        String catalogContextUrl = FieldExtractor.extractContextUrl(catalogNode);
        for (JsonNode resourceNode : FieldExtractor.iterableResources(catalogNode)) {
            if (!FieldExtractor.isRealResource(resourceNode)) continue;
            try {
                pairs.add(new IdAndNode(extractResourceId(resourceNode), resourceNode));
            } catch (Exception e) {
                errors.add(new ProcessingError(null, ProcessingErrorCode.NET_INTERNAL_ERROR,
                        ErrorSanitizer.sanitize(e)));
            }
        }

        List<String> allResourceIds = pairs.stream().map(IdAndNode::resourceId).toList();
        Map<String, Item> existingById = allResourceIds.isEmpty() ? Map.of()
                : itemStore.findAllByIdInAndCatalogId(allResourceIds, catalogId).stream()
                        .collect(Collectors.toMap(Item::getId, Function.identity()));

        List<ResourceWithNode> built = new ArrayList<>();

        // Rows known to already exist, so the insert/update metrics can tell the two apart.
        // Phases 2, 3 and 3.5 all rebuild rows they loaded from the DB, so each records its key here.
        Set<String> preExistingKeys = existingById.values().stream()
                .map(PersistenceStep::rowKey)
                .collect(Collectors.toCollection(HashSet::new));

        // Phase 1: process explicitly listed resources (new or upsert).
        for (IdAndNode pair : pairs) {
            String resourceId = pair.resourceId();
            JsonNode resourceNode = pair.resourceNode();
            try {
                // Resource body is always replaced — Catalg sends a fully resolved resource.
                JsonNode payload = payloadBuilder.buildDenormalizedPayloadFromSlice(baseSlice, resourceNode, offerIndex, resourceId);
                // Offers are not: in MERGE, an offer this publish never mentioned stays attached.
                // Restated offers are excluded, so narrowing resourceIds still detaches them.
                Item existing = existingById.get(resourceId);
                if (!isFullReplace && existing != null
                        && hasUnrestatedOffer(existing, incomingOfferById.keySet())) {
                    mergeService.carryForwardUnrestatedOffers(payload, existing.getPayload(), incomingOfferById.keySet());
                }
                String[] offerIds = payloadBuilder.extractOfferIdsFromPayload(payload);
                String type = Optional.ofNullable(FieldExtractor.extractResourceAttributesType(resourceNode))
                        .orElse(FieldExtractor.extractResourceType(resourceNode));
                String attrsContextUrl = FieldExtractor.extractResourceAttributesContextUrl(resourceNode);
                String resourceContextUrl = FieldExtractor.extractContextUrl(resourceNode);
                String contextUrl = attrsContextUrl != null
                        ? attrsContextUrl
                        : (resourceContextUrl != null ? resourceContextUrl : catalogContextUrl);
                built.add(new ResourceWithNode(
                        Item.from(resourceId, payload.toString(), offerIds,
                                catalogId, type, contextUrl, effectiveNetworkIds.toArray(new String[0])),
                        payload));
            } catch (Exception e) {
                String sanitized = ErrorSanitizer.sanitize(e);
                errors.add(new ProcessingError(resourceId, ProcessingErrorCode.NET_INTERNAL_ERROR, sanitized));
                log.warn("event={} resourceId={} catalogId={} error={}", LogEvent.PERSIST_FAILED, resourceId, catalogId, sanitized);
            }
        }

        // Phase 2: offer propagation — push updated offers to resources NOT in the explicit payload.
        if (!incomingOfferById.isEmpty()) {
            Set<String> explicitIds = new HashSet<>(allResourceIds);
            List<Item> linkedItems = itemStore.findAllByCatalogIdAndAnyOfferId(
                    catalogId, new ArrayList<>(incomingOfferById.keySet()));

            for (Item linkedItem : linkedItems) {
                if (explicitIds.contains(linkedItem.getId())) continue;
                if (!catalogId.equals(linkedItem.getCatalogId())) continue;

                try {
                    JsonNode stale = mergeService.parseOrEmpty(linkedItem.getPayload());
                    boolean changed = false;
                    Map<String, Integer> payloadOfferIndex = null;
                    for (String linkedOfferId : linkedItem.getOfferIds()) {
                        JsonNode incomingOffer = incomingOfferById.get(linkedOfferId);
                        if (incomingOffer != null) {
                            if (payloadOfferIndex == null)
                                payloadOfferIndex = mergeService.buildOfferIndex(stale);
                            mergeService.mergeOfferIntoPayload(stale, incomingOffer, linkedOfferId, payloadOfferIndex);
                            changed = true;
                        }
                    }
                    if (changed) {
                        // Rebuild with this publish's catalog metadata too — otherwise a MERGE that
                        // both renames the catalog and restates an offer on an unlisted resource
                        // would leave that resource with the new offer but the old catalog identity.
                        // Skip when the catalog node is a bare reference (offer-only publish) — same
                        // guard as Phase 3.5, so an offer-only publish can't wipe stored metadata.
                        JsonNode payload = payloadBuilder.describesCatalog(baseSlice)
                                ? payloadBuilder.applyCatalogMetadata(stale, baseSlice)
                                : stale;
                        if (payload == null) {
                            log.warn("event={} itemId={} catalogId={} reason=no-stored-resources",
                                    LogEvent.PERSIST_FAILED, linkedItem.getId(), catalogId);
                        } else {
                            preExistingKeys.add(rowKey(linkedItem));
                            String[] offerIds = payloadBuilder.extractOfferIdsFromPayload(payload);
                            built.add(new ResourceWithNode(
                                    Item.from(linkedItem.getId(), payload.toString(), offerIds,
                                            linkedItem.getCatalogId(),
                                            linkedItem.getType(), linkedItem.getContextUrl(),
                                            linkedItem.getNetworkIds().toArray(new String[0])),
                                    payload));
                            log.debug("event={} itemId={} offers={}", LogEvent.PERSIST_COMPLETED,
                                    linkedItem.getId(), linkedItem.getOfferIds());
                        }
                    }
                } catch (Exception e) {
                    String sanitized = ErrorSanitizer.sanitize(e);
                    errors.add(new ProcessingError(linkedItem.getId(), ProcessingErrorCode.NET_INTERNAL_ERROR, sanitized));
                    log.warn("event={} itemId={} catalogId={} error={}",
                            LogEvent.PERSIST_FAILED, linkedItem.getId(), catalogId, sanitized);
                }
            }
        }

        // Phase 3: Cross-catalog offer resolution — attach offers to resources owned by other catalogs.
        if (!incomingOfferById.isEmpty()) {
            // Phase 2 resources must be in handledIds to prevent Phase 3 (cross-BPP offers) from
            // double-processing resources that were already updated by Phase 2 (same-catalog offer propagation).
            Set<String> handledIds = new HashSet<>(allResourceIds);
            built.forEach(rwn -> handledIds.add(rwn.item().getId()));

            var resolved = offerResolutionStep.resolveCrossBppOffers(incomingOfferById, handledIds, ctx);
            for (var r : resolved) {
                // Cross-catalog targets were loaded from the DB, so they are updates, not inserts.
                preExistingKeys.add(rowKey(r.item()));
                built.add(new ResourceWithNode(r.item(), r.payloadNode()));
            }
        }

        // Phase 3.5: catalog metadata propagation — carry a changed catalog descriptor/provider/
        // validity to this catalog's resources that the publish did not list, so every row of the
        // catalog keeps the same identity. MERGE only; FULL already deleted the prior rows.
        if (!isFullReplace) {
            // Only this catalog's rows count as handled — Phase 3 may have queued items of others.
            Set<String> handledIds = new HashSet<>(allResourceIds);
            built.stream()
                    .filter(rwn -> catalogId.equals(rwn.item().getCatalogId()))
                    .forEach(rwn -> handledIds.add(rwn.item().getId()));

            for (var r : metadataPropagationStep.propagate(
                    catalogId, baseSlice, existingById.values(), handledIds)) {
                // Refreshed rows were loaded from the DB, so they are updates, not inserts.
                preExistingKeys.add(rowKey(r.item()));
                built.add(new ResourceWithNode(r.item(), r.payloadNode()));
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
                .filter(iwn -> !preExistingKeys.contains(rowKey(iwn.item())))
                .count();
        int updateCount = built.size() - insertCount;

        List<Item> savedResources = itemStore.saveAll(built.stream().map(ResourceWithNode::item).toList());
        // MERGE re-derives each published item's locations from its (merged) payload. Clear the
        // published items' existing location rows first so a reduced set of availableAt geometries
        // doesn't leave stale higher-seq rows behind (#306). FULL already cleared the whole catalog
        // above. Scoped to the published item ids — resources absent from a partial MERGE are
        // untouched. Grouped by each item's OWN catalogId, because Phase 3 cross-catalog items
        // carry a different catalogId than the publishing catalog.
        if (!isFullReplace && !savedResources.isEmpty()) {
            savedResources.stream()
                    .collect(Collectors.groupingBy(Item::getCatalogId,
                            Collectors.mapping(Item::getId, Collectors.toList())))
                    .forEach((catId, itemIds) -> locationStore.deleteByItemIdsAndCatalogId(itemIds, catId));
        }
        List<ItemLocationCollection> allLocations = savedResources.stream()
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
        log.info("event={} catalogId={} mode={} resources={} inserted={} updated={} locations={} errors={}",
                LogEvent.PERSIST_COMPLETED, catalogId, updateMode, savedResources.size(),
                insertCount, updateCount, allLocations.size(), errors.size());
        return new CatalogBatch(catalogId, ctx, schemaType, op,
                List.copyOf(savedResources), List.copyOf(errors), Map.copyOf(payloadNodeById), isFullReplace);
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

    /**
     * Fast pre-check for MERGE offer carry-forward: true when the stored row holds at least one
     * offer this publish never mentioned.
     *
     * <p>Reads the already-loaded {@code offer_ids} column instead of parsing the stored payload
     * JSON, which keeps the common case free of work — a publisher sending the catalog's full
     * offer list (the Catalg path) restates every stored id, so the parse is skipped entirely.
     * {@code offer_ids} is derived from the payload on every write, so the two stay in step.
     */
    private static boolean hasUnrestatedOffer(Item existing, Set<String> restatedOfferIds) {
        for (String storedOfferId : existing.getOfferIds()) {
            if (!restatedOfferIds.contains(storedOfferId)) return true;
        }
        return false;
    }

    /** Identity of a row in the {@code item} table — the PK is (id, catalog_id), not id alone. */
    private static String rowKey(Item item) {
        return item.getCatalogId() + '|' + item.getId();
    }

    /**
     * Reads {@code visibleTo} from the message-level {@code publishDirectives} array by matching
     * on {@code catalogId}. Returns an empty list when no matching directive or empty {@code visibleTo}.
     *
     * <p>When present, {@code visibleTo} contains the full set of network IDs this catalog is
     * visible to — set by the publisher via Catalg's multi-network publish feature.
     */
    private List<String> extractVisibleTo(JsonNode messageNode, String catalogId) {
        if (messageNode == null || messageNode.isMissingNode() || messageNode.isNull()) {
            return List.of();
        }
        var directives = messageNode.path(BecknFields.PUBLISH_DIRECTIVES);
        if (directives.isArray()) {
            for (var d : directives) {
                if (catalogId.equals(d.path("catalogId").asText(null))) {
                    var vtNode = d.path(BecknFields.VISIBLE_TO);
                    if (vtNode.isArray() && !vtNode.isEmpty()) {
                        var result = new ArrayList<String>();
                        for (var n : vtNode) {
                            if (n.isTextual() && !n.asText().isBlank()) {
                                result.add(n.asText());
                            }
                        }
                        return List.copyOf(result);
                    }
                }
            }
        }
        return List.of();
    }

    private String extractResourceId(JsonNode resourceNode) {
        return FieldExtractor.extractString(resourceNode, BecknFields.ID)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new FieldExtractionException("Resource missing id"));
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
                entities.add(ProviderOffer.from(offerId, catalogId, providerId, payload));
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
