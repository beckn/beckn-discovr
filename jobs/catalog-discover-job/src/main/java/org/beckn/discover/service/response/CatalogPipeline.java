package org.beckn.discover.service.response;

import org.beckn.discover.logging.LogEvent;
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
 *   <li><b>filterResourcesByOfferReferences</b> — when an offer-scoped query
 *       has populated offers, restricts resources to only those referenced by
 *       at least one offer.</li>
 *   <li><b>filterOffersByResourceIds</b> — removes offers that reference none
 *       of the catalog's resources (cross-filter in the opposite direction).</li>
 *   <li><b>removeEmptyCatalogs</b> — discards catalogs that have no resources
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
     * <p>Delegates to {@link #process(List, QueryRequest, boolean)} with
     * {@code schemaPreFiltered=false}, so step 1 (schema context filtering)
     * always runs. This is the correct choice for NLWeb-backed queries where
     * ES/PG has not already applied schema filtering.</p>
     *
     * @param catalogs raw catalogs from any engine/assembler; {@code null}
     *                 is treated as empty
     * @param request  provides schema context URLs for the filtering step
     * @return processed catalogs; never {@code null}, may be empty
     */
    public List<Catalog> process(List<Catalog> catalogs, QueryRequest request) {
        return process(catalogs, request, false);
    }

    /**
     * Runs the post-processing pipeline on {@code catalogs} and returns the
     * processed list.
     *
     * <p>When {@code schemaPreFiltered} is {@code true}, step 1 (schema context
     * filtering) is skipped because the query engine has already applied it at
     * the ES or PostgreSQL layer. Pass {@code true} for ES and PostgreSQL paths;
     * pass {@code false} (or use the 2-arg overload) for NLWeb paths.</p>
     *
     * <p>The input list is not mutated; a new mutable list is used
     * internally.</p>
     *
     * @param catalogs         raw catalogs from any engine/assembler; {@code null}
     *                         is treated as empty
     * @param request          provides schema context URLs for the filtering step
     * @param schemaPreFiltered when {@code true}, step 1 is skipped
     * @return processed catalogs; never {@code null}, may be empty
     */
    public List<Catalog> process(List<Catalog> catalogs, QueryRequest request, boolean schemaPreFiltered) {
        if (catalogs == null || catalogs.isEmpty()) {
            log.debug("event={}", LogEvent.PIPELINE_EMPTY);
            return List.of();
        }

        long startNanos = System.nanoTime();
        int inputSize = catalogs.size();

        // Work on a mutable copy so we can remove empty catalogs at the end
        List<Catalog> mutableCatalogs = new ArrayList<>(catalogs);

        if (!schemaPreFiltered) {
            filterResourcesBySchemaContext(mutableCatalogs, request);
        } else {
            log.debug("event={} reason=schema-pre-filtered", LogEvent.PIPELINE_STEP1_SKIPPED);
        }
        deduplicateOffersInCatalogs(mutableCatalogs);
        filterResourcesByOfferReferences(mutableCatalogs);
        filterOffersByResourceIds(mutableCatalogs);
        removeEmptyCatalogs(mutableCatalogs, request.transactionId());

        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("event={} input={} output={} resources={} durationMs={} schemaPreFiltered={}",
                LogEvent.PIPELINE_COMPLETED, inputSize,
                mutableCatalogs.size(),
                mutableCatalogs.stream().mapToInt(c -> c.getResources() != null ? c.getResources().size() : 0).sum(),
                ms, schemaPreFiltered);

        return mutableCatalogs;
    }

    // ── Pipeline steps ────────────────────────────────────────────────────────

    /**
     * Filter resources by schema context URL.
     * No-op when the request has no schema context filter.
     */
    private void filterResourcesBySchemaContext(List<Catalog> catalogs, QueryRequest request) {
        if (request.schemaContextUrls().isEmpty()) return;

        int beforeCount = totalResourceCount(catalogs);
        processor.filterCatalogsBySchemaContext(catalogs, request.schemaContextUrls());
        int afterCount = totalResourceCount(catalogs);

        if (beforeCount != afterCount) {
            log.debug("event={} removed={}", LogEvent.PIPELINE_STEP1_SCHEMA_FILTER, beforeCount - afterCount);
        }
    }

    /**
     * Remove duplicate offers within each catalog.
     * No-op when a catalog has ≤1 offer.
     */
    private void deduplicateOffersInCatalogs(List<Catalog> catalogs) {
        catalogs.forEach(processor::deduplicateOffers);
    }

    /**
     * Restrict resources to those referenced by offers.
     * Applies only when offers are present; otherwise a no-op.
     */
    private void filterResourcesByOfferReferences(List<Catalog> catalogs) {
        catalogs.forEach(processor::filterResourcesByOfferReferences);
    }

    /**
     * Remove offers that reference none of the catalog's resources.
     * Always safe; no-op when no offers are present.
     */
    private void filterOffersByResourceIds(List<Catalog> catalogs) {
        catalogs.forEach(processor::filterOffersByResourceIds);
    }

    /**
     * Remove catalogs that have no resources after the preceding steps.
     */
    private void removeEmptyCatalogs(List<Catalog> catalogs, String transactionId) {
        int before = catalogs.size();
        catalogs.removeIf(c -> {
            boolean empty = c.getResources() == null || c.getResources().isEmpty();
            if (empty) {
                log.debug("event={} id={}", LogEvent.PIPELINE_STEP5_REMOVED_EMPTY, c.getId());
            }
            return empty;
        });
        if (catalogs.size() < before) {
            log.debug("event={} count={}", LogEvent.PIPELINE_STEP5_REMOVED, before - catalogs.size());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int totalResourceCount(List<Catalog> catalogs) {
        return catalogs.stream()
                .map(Catalog::getResources)
                .filter(Objects::nonNull)
                .mapToInt(List::size)
                .sum();
    }
}
