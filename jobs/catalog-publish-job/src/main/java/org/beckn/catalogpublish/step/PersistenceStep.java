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
import org.beckn.catalogpublish.service.geometry.GeometryExtractor;
import org.beckn.catalogpublish.service.payload.ItemPayloadBuilder;
import org.beckn.catalogpublish.service.payload.PayloadMergeService;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.common.SchemaVersion;
import org.beckn.catalogpublish.store.ItemLocationCollectionStore;
import org.beckn.catalogpublish.util.BecknFieldNormalizer;
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
    private final ItemPayloadBuilder payloadBuilder;
    private final PayloadMergeService mergeService;
    private final GeometryExtractor geometryExtractor;
    private final ObjectMapper objectMapper;

    public PersistenceStep(ItemStore itemStore,
            ItemLocationCollectionStore locationStore,
            ItemPayloadBuilder payloadBuilder,
            PayloadMergeService mergeService,
            GeometryExtractor geometryExtractor,
            ObjectMapper objectMapper) {
        this.itemStore = itemStore;
        this.locationStore = locationStore;
        this.payloadBuilder = payloadBuilder;
        this.mergeService = mergeService;
        this.geometryExtractor = geometryExtractor;
        this.objectMapper = objectMapper;
    }

    /**
     * Persists all items from a catalog node using an upsert strategy:
     * <ul>
     * <li><b>New item</b>: builds a fresh denormalised payload from catalog
     * metadata + item + resolved offers.</li>
     * <li><b>Existing item (explicit)</b>: merges incoming item fields (RFC 7396
     * merge-patch) then re-applies
     * all incoming offers linked to that item.</li>
     * <li><b>Existing item (offer propagation)</b>: if an incoming offer update
     * links to items that are NOT in
     * the explicit payload, those items are fetched by the {@code offer_ids} column
     * and the updated offer
     * is merged into their stored payloads. This ensures a single offer change is
     * reflected in every item
     * that references it — not just the ones explicitly listed in the request.</li>
     * </ul>
     * Two batch DB lookups are issued: one for explicit item IDs, one for
     * offer-linked items. No per-item queries.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CatalogBatch persistItemsAndLocations(JsonNode catalogNode, CatalogContext ctx, CatalogOperation op) {
        String catalogId = FieldExtractor.requireString(catalogNode, "id");
        JsonNode allOffers = FieldExtractor.extractOffersOrEmpty(catalogNode);
        String schemaType = FieldExtractor.extractSchemaTypeFromItems(catalogNode);

        ObjectNode baseSlice = payloadBuilder.buildCatalogMetadataSlice(catalogNode, ctx);
        OfferIndex offerIndex = OfferIndex.build(allOffers, objectMapper);

        // Build the incoming offer map early — needed by both Phase 1 (upsert) and
        // Phase 2 (propagation).
        // Keyed by beckn:id for O(1) lookup; offers without an id are skipped.
        Map<String, JsonNode> incomingOfferById = buildIncomingOfferMap(allOffers);

        // Phase 0: collect (itemId, originalItemNode, normalizedItemNode) triples in declaration order.
        // Items with unresolvable IDs go straight to errors.
        // Normalization happens here so that extractItemId can always use the canonical "id" field name,
        // while the original (unnormalized) node is preserved for payload storage.
        record IdAndNode(String itemId, JsonNode itemNode, JsonNode normalizedItemNode) {
        }
        List<IdAndNode> pairs = new ArrayList<>();
        List<ProcessingError> errors = new ArrayList<>();
        String catalogContextUrl = FieldExtractor.extractContextUrl(catalogNode);
        for (JsonNode itemNode : FieldExtractor.iterableItems(catalogNode)) {
            try {
                JsonNode normalizedForId = BecknFieldNormalizer.normalizeItem(itemNode, objectMapper);
                pairs.add(new IdAndNode(extractItemId(normalizedForId), itemNode, normalizedForId));
            } catch (Exception e) {
                errors.add(new ProcessingError("unknown", ProcessingErrorCode.NET_INTERNAL_ERROR,
                        ErrorSanitizer.sanitize(e)));
            }
        }

        // Single batch DB lookup — avoids N individual queries for the upsert check.
        List<String> allItemIds = pairs.stream().map(IdAndNode::itemId).toList();
        Map<String, Item> existingById = allItemIds.isEmpty() ? Map.of()
                : itemStore.findAllByIdInAndBppId(allItemIds, ctx.bppId()).stream()
                        .collect(Collectors.toMap(Item::getId, Function.identity()));

        // Keep the already-parsed JsonNode alongside the Item to avoid re-parsing for
        // geometry extraction later.
        record ItemWithNode(Item item, JsonNode payloadNode) {
        }
        List<ItemWithNode> built = new ArrayList<>();

        // Phase 1: process explicitly listed items (new or upsert).
        for (IdAndNode pair : pairs) {
            String itemId = pair.itemId();
            JsonNode itemNode = pair.itemNode();            // original — stored in DB
            JsonNode normalizedItemNode = pair.normalizedItemNode(); // canonical — used for extraction
            try {
                // Detect schema version from the original (unnormalized) item node.
                SchemaVersion version = BecknFieldNormalizer.detectVersion(itemNode);

                Item existing = existingById.get(itemId);
                JsonNode payload;
                if (existing != null) {
                    // Upsert path: null-safe merge — strip nulls first so that a null in the
                    // incoming publish payload means "no change" rather than RFC 7396 "delete".
                    payload = mergeService.mergeItemPayload(existing.getPayload(),
                            mergeService.stripNulls(itemNode));

                    // Determine which incoming offers apply to this item.
                    // Primary source of truth: the offer_ids DB column (tracks all linked offers
                    // regardless of what offer.beckn:items says in the current request).
                    // Secondary: offer.id — allows newly declared linkages in this request.
                    Set<String> offerIdsToApply = new HashSet<>();
                    if (existing.getOfferIds() != null)
                        offerIdsToApply.addAll(Arrays.asList(existing.getOfferIds()));
                    offerIndex.getOffersForItem(itemId, objectMapper)
                            .forEach(o -> FieldExtractor.extractString(o, BecknFields.ID)
                                    .ifPresent(offerIdsToApply::add));

                    if (!offerIdsToApply.isEmpty()) {
                        // Build an O(1) index once per item; avoids O(n) array scan per offer.
                        Map<String, Integer> payloadOfferIndex = mergeService.buildOfferIndex(payload);
                        for (String offerId : offerIdsToApply) {
                            JsonNode incomingOffer = incomingOfferById.get(offerId);
                            if (incomingOffer != null)
                                mergeService.mergeOfferIntoPayload(payload,
                                        mergeService.stripNulls(incomingOffer), offerId, payloadOfferIndex);
                        }
                    }
                } else {
                    // New item path: build fresh denorm from catalog metadata + item + resolved
                    // offers.
                    payload = payloadBuilder.buildDenormalizedPayloadFromSlice(baseSlice, itemNode, offerIndex, itemId);
                }
                String[] offerIds = payloadBuilder.extractOfferIdsFromPayload(payload);
                // Use normalized node for all field extraction — field names are canonical here.
                String name = FieldExtractor.extractItemName(normalizedItemNode);
                String type = Optional.ofNullable(FieldExtractor.extractItemAttributesType(normalizedItemNode))
                        .orElse(FieldExtractor.extractItemType(normalizedItemNode));
                String providerId = FieldExtractor.extractItemProviderId(normalizedItemNode);
                String attrsContextUrl = FieldExtractor.extractItemAttributesContextUrl(normalizedItemNode);
                String itemContextUrl = FieldExtractor.extractContextUrl(normalizedItemNode);
                String contextUrl = attrsContextUrl != null
                        ? attrsContextUrl
                        : (itemContextUrl != null ? itemContextUrl : catalogContextUrl);
                built.add(new ItemWithNode(
                        Item.from(itemId, payload.toString(), offerIds, ctx, catalogId, name, type, providerId,
                                contextUrl, version.getValue()),
                        payload));
            } catch (Exception e) {
                String sanitized = ErrorSanitizer.sanitize(e);
                errors.add(new ProcessingError(itemId, ProcessingErrorCode.NET_INTERNAL_ERROR, sanitized));
                log.warn("item.build.failed itemId={} catalogId={} error={}", itemId, catalogId, sanitized);
            }
        }

        // Phase 2: offer propagation — push updated offers to items NOT in the explicit
        // payload.
        //
        // If an offer's data changed (e.g. new price), every item that stores that
        // offer in its
        // denormalized payload must be refreshed, even if the caller did not list those
        // items
        // explicitly. We query by the offer_ids[] column, then filter to the same
        // catalog and
        // skip any item already handled in Phase 1.
        if (!incomingOfferById.isEmpty()) {
            Set<String> explicitIds = new HashSet<>(allItemIds);
            List<Item> linkedItems = itemStore.findAllByBppIdAndAnyOfferId(
                    ctx.bppId(), new ArrayList<>(incomingOfferById.keySet()));

            for (Item linkedItem : linkedItems) {
                if (explicitIds.contains(linkedItem.getId()))
                    continue; // already handled in Phase 1
                if (!catalogId.equals(linkedItem.getCatalogId()))
                    continue; // different catalog — skip

                try {
                    JsonNode payload = mergeService.parseOrEmpty(linkedItem.getPayload());
                    boolean changed = false;
                    // Build index once per propagated item — same O(1) benefit as Phase 1.
                    Map<String, Integer> payloadOfferIndex = null;
                    for (String linkedOfferId : linkedItem.getOfferIds()) {
                        JsonNode incomingOffer = incomingOfferById.get(linkedOfferId);
                        if (incomingOffer != null) {
                            if (payloadOfferIndex == null)
                                payloadOfferIndex = mergeService.buildOfferIndex(payload);
                            mergeService.mergeOfferIntoPayload(
                                    payload, mergeService.stripNulls(incomingOffer),
                                    linkedOfferId, payloadOfferIndex);
                            changed = true;
                        }
                    }
                    if (changed) {
                        String[] offerIds = payloadBuilder.extractOfferIdsFromPayload(payload);
                        built.add(new ItemWithNode(
                                Item.from(linkedItem.getId(), payload.toString(), offerIds, ctx,
                                        catalogId, linkedItem.getName(), linkedItem.getType(),
                                        linkedItem.getProviderId(), linkedItem.getContextUrl(),
                                        linkedItem.getSchemaVersion()),
                                payload));
                        log.debug("offer.propagated itemId={} offers={}", linkedItem.getId(),
                                Arrays.toString(linkedItem.getOfferIds()));
                    }
                } catch (Exception e) {
                    String sanitized = ErrorSanitizer.sanitize(e);
                    errors.add(
                            new ProcessingError(linkedItem.getId(), ProcessingErrorCode.NET_INTERNAL_ERROR, sanitized));
                    log.warn("offer.propagation.failed itemId={} catalogId={} error={}",
                            linkedItem.getId(), catalogId, sanitized);
                }
            }
        }

        if (built.isEmpty()) {
            return new CatalogBatch(catalogId, ctx, schemaType, op, List.of(), List.copyOf(errors), Map.of());
        }

        // Build a lookup map so we can pass the pre-parsed JsonNode to
        // GeometryExtractor — no re-parse.
        Map<String, JsonNode> payloadNodeById = new HashMap<>();
        built.forEach(p -> payloadNodeById.put(p.item().getId(), p.payloadNode()));

        List<Item> savedItems = itemStore.saveAll(built.stream().map(ItemWithNode::item).toList());
        List<ItemLocationCollection> allLocations = savedItems.stream()
                .flatMap(item -> {
                    JsonNode node = payloadNodeById.get(item.getId());
                    return (node != null
                            ? geometryExtractor.extractLocations(item.getId(), node)
                            : geometryExtractor.extractLocations(item.getId(), item.getPayload())).stream();
                })
                .toList();
        locationStore.saveLocations(allLocations);
        log.info("catalog.persisted catalogId={} items={} locations={} errors={}",
                catalogId, savedItems.size(), allLocations.size(), errors.size());
        return new CatalogBatch(catalogId, ctx, schemaType, op,
                List.copyOf(savedItems), List.copyOf(errors), Map.copyOf(payloadNodeById));
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private String extractItemId(JsonNode itemNode) {
        return FieldExtractor.extractString(itemNode, BecknFields.ID)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new FieldExtractionException("Item missing id"));
    }

    /**
     * Indexes incoming offers by {@code id} for O(1) lookup during Phase 2 propagation.
     * Offers missing an ID are silently skipped — they cannot be matched to stored items.
     */
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
}
