package org.beckn.catalogpublish.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.metrics.CatalogPublishMetrics;
import org.beckn.catalogpublish.model.Item;
import org.beckn.catalogpublish.service.payload.ItemPayloadBuilder;
import org.beckn.catalogpublish.service.payload.PayloadMergeService;
import org.beckn.catalogpublish.store.ItemStore;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Phase 3.5 of the persistence pipeline: catalog metadata propagation.
 *
 * <p>Catalog-level properties (descriptor, provider, validity, …) have no table of their own —
 * every {@code item} row carries its own copy. So a MERGE publish that renames the catalog while
 * listing only some of its resources would leave the rest holding the old name, and the same
 * catalog would answer discover with two different identities.
 *
 * <p>This step rewrites the rows the publish did not reach, keeping each row's own resource and
 * offers untouched. The refreshed items flow through the caller's normal save + index path, so
 * PostgreSQL and Elasticsearch both pick up the change with no extra work here.
 *
 * <p>MERGE only — FULL replace has already deleted every prior row of the catalog.
 *
 * <p>Cost is kept off the common path: the change detection reuses a row the publish already
 * loaded, and the catalog-wide read only happens once metadata has actually changed. When it has,
 * every remaining row of the catalog is rewritten — fine for the catalog sizes in play, and worth
 * revisiting with a jsonb bulk update if one catalog ever reaches ~100k resources.
 *
 * <p>Failures are logged and counted rather than returned: a row the publisher never mentioned
 * should not turn its publish into a partial failure. Same convention as {@link OfferResolutionStep}.
 */
@Service
public class CatalogMetadataPropagationStep {

    private static final Logger log = LoggerFactory.getLogger(CatalogMetadataPropagationStep.class);

    /** A row rewritten to carry the publish's catalog metadata. */
    public record RefreshedItem(Item item, JsonNode payloadNode) {}

    private final ItemStore itemStore;
    private final PayloadMergeService mergeService;
    private final ItemPayloadBuilder payloadBuilder;
    private final CatalogPublishMetrics metrics;

    public CatalogMetadataPropagationStep(ItemStore itemStore,
            PayloadMergeService mergeService,
            ItemPayloadBuilder payloadBuilder,
            CatalogPublishMetrics metrics) {
        this.itemStore = itemStore;
        this.mergeService = mergeService;
        this.payloadBuilder = payloadBuilder;
        this.metrics = metrics;
    }

    /**
     * @param catalogId       the publishing catalog
     * @param baseSlice       the publish's catalog metadata (no resources, no offers)
     * @param alreadyLoaded   rows the publish already read from the DB, used as a free sample of
     *                        the catalog's stored metadata
     * @param alreadyHandled  resource ids of this catalog that Phases 1–3 have already rebuilt
     * @return the rows rewritten, empty when metadata is unchanged or the catalog is new
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<RefreshedItem> propagate(String catalogId, ObjectNode baseSlice,
            Collection<Item> alreadyLoaded, Set<String> alreadyHandled) {

        // An offer-only publish carries the catalog as a bare reference — {"id": …} and nothing
        // else — purely to say which catalog the offers belong to. That is not a claim that the
        // catalog has lost its descriptor, provider and validity, so propagating it would erase
        // real metadata from every row. Only propagate once the publish has actually described the
        // catalog; from then on its slice is authoritative, omissions included, exactly as it
        // already is for the resources the publish lists.
        if (!describesCatalog(baseSlice)) {
            log.debug("event={} catalogId={} reason=catalog-node-is-a-reference",
                    LogEvent.CATALOG_META_SKIPPED, catalogId);
            return List.of();
        }

        // Sample one stored row to see what metadata the catalog currently holds. Prefer a row the
        // publish already loaded (free); only query when nothing it listed exists yet.
        Item sample = alreadyLoaded.stream().findFirst()
                .orElseGet(() -> itemStore.findFirstByCatalogId(catalogId).orElse(null));
        if (sample == null) return List.of(); // catalog has no stored rows — nothing to propagate to

        if (!payloadBuilder.catalogMetadataDiffers(mergeService.parseOrEmpty(sample.getPayload()), baseSlice)) {
            log.debug("event={} catalogId={} reason=metadata-unchanged", LogEvent.CATALOG_META_SKIPPED, catalogId);
            return List.of();
        }

        var refreshed = new ArrayList<RefreshedItem>();
        int skipped = 0;
        for (Item stale : itemStore.findAllByCatalogId(catalogId)) {
            if (alreadyHandled.contains(stale.getId())) continue;
            try {
                JsonNode payload = payloadBuilder.applyCatalogMetadata(
                        mergeService.parseOrEmpty(stale.getPayload()), baseSlice);
                if (payload == null) {
                    skipped++;
                    continue;
                }
                refreshed.add(new RefreshedItem(
                        Item.from(stale.getId(), payload.toString(),
                                payloadBuilder.extractOfferIdsFromPayload(payload),
                                stale.getCatalogId(), stale.getType(), stale.getContextUrl(),
                                stale.getNetworkIds().toArray(new String[0])),
                        payload));
            } catch (Exception e) {
                skipped++;
                log.warn("event={} itemId={} catalogId={} error={}", LogEvent.CATALOG_META_SKIPPED,
                        stale.getId(), catalogId, ErrorSanitizer.sanitize(e));
            }
        }

        if (skipped > 0) {
            log.warn("event={} catalogId={} skipped={}", LogEvent.CATALOG_META_SKIPPED, catalogId, skipped);
        }
        if (!refreshed.isEmpty()) {
            metrics.recordCatalogMetadataPropagated(refreshed.size());
            log.info("event={} catalogId={} refreshed={}",
                    LogEvent.CATALOG_META_PROPAGATED, catalogId, refreshed.size());
        }
        return List.copyOf(refreshed);
    }

    /**
     * Whether the publish describes the catalog rather than merely naming it. Keys on the two fields
     * that identify a catalog to a consumer — {@code descriptor} and {@code provider} — so a node
     * holding only ids and routing fields is read as a reference and left to affect nothing.
     */
    private static boolean describesCatalog(ObjectNode baseSlice) {
        return baseSlice.hasNonNull(BecknFields.DESCRIPTOR) || baseSlice.hasNonNull(BecknFields.PROVIDER);
    }
}
