package org.beckn.discover.service.response;

import org.beckn.discover.model.Catalog;
import org.beckn.discover.service.engine.QueryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies a deterministic, ordered sequence of post-processing steps to a
 * raw list of {@link Catalog} objects produced by any
 * {@link org.beckn.discover.service.engine.QueryEngine} or
 * {@link org.beckn.discover.service.engine.TextSearchEngine}.
 *
 * <h3>Pipeline steps (in execution order)</h3>
 * <ol>
 *   <li><b>filterBySchemaContext</b> — removes items whose context URL /
 *       type does not match the request's {@code schema_context} URLs.
 *       PostgreSQL already filters in SQL; this step acts as a safety net
 *       and is the <em>primary</em> filter for NLWeb / Elasticsearch.</li>
 *   <li><b>deduplicateOffers</b> — removes duplicate offers within each
 *       catalog (by {@code id}).</li>
 *   <li><b>filterItemsByOfferReferences</b> — when an offer-scoped query
 *       has populated offers, restricts items to only those referenced by
 *       at least one offer.</li>
 *   <li><b>filterOffersByItemIds</b> — removes offers that reference none
 *       of the catalog's items (cross-filter in the opposite direction).</li>
 *   <li><b>removeEmptyCatalogs</b> — discards catalogs that have no items
 *       after the preceding steps.</li>
 * </ol>
 *
 * <h3>No-op safety</h3>
 * Every step is designed to be a no-op when its preconditions are not met
 * (e.g. empty offers list, no schema context filter, etc.).  The pipeline
 * can therefore be run unconditionally after every query path.
 *
 * <h3>Thread safety</h3>
 * This component is stateless and safe for concurrent use.
 */
@Component
public class CatalogPipeline {

    private static final Logger log = LoggerFactory.getLogger(CatalogPipeline.class);

    private final CatalogProcessor processor;

    public CatalogPipeline(CatalogProcessor processor) {
        this.processor = processor;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Runs the full post-processing pipeline on {@code catalogs} and returns
     * the processed list.
     *
     * <p>The input list is not mutated; a new mutable list is used
     * internally.</p>
     *
     * @param catalogs raw catalogs from any engine/assembler; {@code null}
     *                 is treated as empty
     * @param request  provides schema context URLs for the filtering step
     * @return processed catalogs; never {@code null}, may be empty
     */
    public List<Catalog> process(List<Catalog> catalogs, QueryRequest request) {
        if (catalogs == null || catalogs.isEmpty()) {
            log.debug("pipeline.empty transactionId={}", request.transactionId());
            return List.of();
        }

        long t0 = System.nanoTime();
        int inputSize = catalogs.size();

        // Work on a mutable copy so we can remove empty catalogs at the end
        List<Catalog> work = new ArrayList<>(catalogs);

        step1FilterBySchemaContext(work, request);
        step2DeduplicateOffers(work);
        step3FilterItemsByOfferReferences(work);
        step4FilterOffersByItemIds(work);
        step5RemoveEmptyCatalogs(work, request.transactionId());

        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("pipeline.done input={} output={} resources={} durationMs={} transactionId={}",
                inputSize,
                work.size(),
                work.stream().mapToInt(c -> c.getResources() != null ? c.getResources().size() : 0).sum(),
                ms,
                request.transactionId());

        return work;
    }

    // ── Pipeline steps ────────────────────────────────────────────────────────

    /**
     * Step 1 — Filter items by schema context URL.
     * No-op when the request has no schema context filter.
     */
    private void step1FilterBySchemaContext(List<Catalog> catalogs, QueryRequest request) {
        if (request.schemaContextUrls().isEmpty()) return;

        int beforeItems = totalItems(catalogs);
        processor.filterCatalogsBySchemaContext(catalogs, request.schemaContextUrls());
        int afterItems = totalItems(catalogs);

        if (beforeItems != afterItems) {
            log.debug("pipeline.step1.schemaFilter removed={} transactionId={}",
                    beforeItems - afterItems, request.transactionId());
        }
    }

    /**
     * Step 2 — Remove duplicate offers within each catalog.
     * No-op when a catalog has ≤1 offer.
     */
    private void step2DeduplicateOffers(List<Catalog> catalogs) {
        catalogs.forEach(processor::deduplicateOffers);
    }

    /**
     * Step 3 — Restrict items to those referenced by offers.
     * Applies only when offers are present; otherwise a no-op.
     */
    private void step3FilterItemsByOfferReferences(List<Catalog> catalogs) {
        catalogs.forEach(processor::filterItemsByOfferReferences);
    }

    /**
     * Step 4 — Remove offers that reference none of the catalog's items.
     * Always safe; no-op when no offers are present.
     */
    private void step4FilterOffersByItemIds(List<Catalog> catalogs) {
        catalogs.forEach(processor::filterOffersByItemIds);
    }

    /**
     * Step 5 — Remove catalogs that have no items after the preceding steps.
     */
    private void step5RemoveEmptyCatalogs(List<Catalog> catalogs, String transactionId) {
        int before = catalogs.size();
        catalogs.removeIf(c -> {
            boolean empty = c.getResources() == null || c.getResources().isEmpty();
            if (empty) {
                log.debug("pipeline.step5.removedEmptyCatalog id={} transactionId={}",
                        c.getId(), transactionId);
            }
            return empty;
        });
        if (catalogs.size() < before) {
            log.info("pipeline.step5.removed count={} transactionId={}",
                    before - catalogs.size(), transactionId);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int totalItems(List<Catalog> catalogs) {
        return catalogs.stream()
                .map(Catalog::getResources)
                .filter(Objects::nonNull)
                .mapToInt(List::size)
                .sum();
    }
}
