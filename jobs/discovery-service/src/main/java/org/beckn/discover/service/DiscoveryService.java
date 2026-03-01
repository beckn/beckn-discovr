package org.beckn.discover.service;

import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Context;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.model.DiscoverResponse;
import org.beckn.discover.service.engine.QueryEngine;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.engine.TextSearchEngine;
import org.beckn.discover.service.response.CatalogPipeline;
import org.beckn.discover.service.response.ResponseProcessor;
import org.beckn.discover.util.LatencyTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Main orchestration service for discovery request processing.
 *
 * <h3>Query routing</h3>
 * <pre>
 * DiscoverRequest
 *        │
 *        ├── filters + spatial → Path A: queryEngine.executeCombinedQuery()
 *        │                        Optional.empty() → parallel B ∥ C + intersect
 *        ├── filters only      → Path B: queryEngine.executeFilterQuery()
 *        ├── spatial only      → Path C: queryEngine.executeSpatialQuery()
 *        └── neither           → Path D: textSearchEngine.search()
 * </pre>
 *
 * <h3>Post-processing</h3>
 * After every path the raw {@code List<Catalog>} passes through
 * {@link CatalogPipeline} (schema filter → dedup offers → cross-filter items /
 * offers → remove empty catalogs) before the response is assembled.
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li>Constructor injection — all fields are {@code final} (AR-3.1).</li>
 *   <li>Path A fallback uses {@code Optional<List<Catalog>>} to distinguish
 *       "no spatial conditions built" from "query ran, zero results" — this
 *       fixes the previous bug where an empty result list incorrectly
 *       triggered the parallel fallback.</li>
 *   <li>MDC is captured before spawning parallel futures so that all threads
 *       carry the same transaction / message / BAP identifiers (NFR-3.3).</li>
 *   <li>Parallel queries use a dedicated I/O thread pool
 *       ({@code discoveryQueryExecutor}), not the JVM ForkJoinPool common
 *       pool.</li>
 * </ul>
 */
@Service
public class DiscoveryService {

    private static final Logger log     = LoggerFactory.getLogger(DiscoveryService.class);
    private static final Logger perfLog = LoggerFactory.getLogger("org.beckn.discover.performance");

    private final QueryEngine        queryEngine;
    private final TextSearchEngine   textSearchEngine;
    private final CatalogPipeline    catalogPipeline;
    private final ResponseProcessor  responseProcessor;
    private final DiscoveryMetrics   metrics;
    private final DiscoveryProperties properties;
    private final ExecutorService    queryExecutor;

    public DiscoveryService(
            QueryEngine                            queryEngine,
            TextSearchEngine                       textSearchEngine,
            CatalogPipeline                        catalogPipeline,
            ResponseProcessor                      responseProcessor,
            DiscoveryMetrics                       metrics,
            DiscoveryProperties                    properties,
            @Qualifier("discoveryQueryExecutor") ExecutorService queryExecutor) {
        this.queryEngine      = queryEngine;
        this.textSearchEngine = textSearchEngine;
        this.catalogPipeline  = catalogPipeline;
        this.responseProcessor = responseProcessor;
        this.metrics           = metrics;
        this.properties        = properties;
        this.queryExecutor     = queryExecutor;
    }

    // ── Public entry points ──────────────────────────────────────────────────

    /**
     * Synchronous entry point.  Validates, sets up MDC, routes to the correct
     * query path, applies the pipeline, and assembles the response.
     */
    public DiscoverResponse processDiscoveryRequest(DiscoverRequest request) {
        validateRequest(request);
        setupMDC(request.getContext());
        metrics.incrementTotalRequests();

        Instant start = Instant.now();
        LatencyTracker tracker = properties.isLatencyTrackingEnabled() ? new LatencyTracker() : null;

        try {
            log.info("discovery.request.start transactionId={} messageId={}",
                    request.getContext().getTransactionId(),
                    request.getContext().getMessageId());

            QueryRequest qr = QueryRequest.from(request);
            DiscoverResponse response = route(qr, request.getContext(), tracker);

            long ms = Duration.between(start, Instant.now()).toMillis();
            metrics.recordSuccess(start);
            log.info("discovery.request.success durationMs={} transactionId={}", ms, qr.transactionId());
            perfLog.info("discovery.request durationMs={} transactionId={}", ms, qr.transactionId());

            return response;

        } catch (Exception e) {
            metrics.recordFailure(start, e, request.getContext().getTransactionId());
            log.error("discovery.request.failed transactionId={} error={}",
                    request.getContext().getTransactionId(), e.getMessage(), e);
            throw new RuntimeException("Failed to process discovery request", e);
        } finally {
            if (tracker != null) tracker.logSummary(request.getContext().getTransactionId(),
                    metrics.getProcessingStats().successfulRequests() > 0);
            clearMDC();
        }
    }

    /**
     * Asynchronous entry point.  Runs {@link #processDiscoveryRequest} on the
     * calling thread's MDC context so log correlation is preserved.
     */
    public CompletableFuture<DiscoverResponse> processDiscoveryRequestAsync(DiscoverRequest request) {
        Map<String, String> mdcCopy = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> {
            restoreMDC(mdcCopy);
            try {
                return processDiscoveryRequest(request);
            } finally {
                MDC.clear();
            }
        }, queryExecutor);
    }

    // ── Query routing ────────────────────────────────────────────────────────

    private DiscoverResponse route(QueryRequest qr, Context context, LatencyTracker tracker)
            throws Exception {

        if (qr.hasFilters() && qr.hasSpatial()) {
            log.info("discovery.path=A transactionId={}", qr.transactionId());
            return pathA(qr, context, tracker);
        }
        if (qr.hasFilters()) {
            log.info("discovery.path=B transactionId={}", qr.transactionId());
            return pathB(qr, context, tracker);
        }
        if (qr.hasSpatial()) {
            log.info("discovery.path=C transactionId={}", qr.transactionId());
            return pathC(qr, context, tracker);
        }
        log.info("discovery.path=D transactionId={}", qr.transactionId());
        return pathD(qr, context, tracker);
    }

    // ── Path A: combined filter + spatial ─────────────────────────────────────

    /**
     * Attempts a single-round-trip combined query.  Falls back to parallel
     * B ∥ C when the engine signals that no spatial conditions could be built
     * ({@code Optional.empty()}).
     *
     * <p><b>Bug fix</b>: the previous implementation fell back on an empty
     * result list, which incorrectly re-ran queries when a combined query
     * returned zero valid matches.  Using {@code Optional} correctly
     * distinguishes the two outcomes.</p>
     */
    private DiscoverResponse pathA(QueryRequest qr, Context context, LatencyTracker tracker)
            throws Exception {

        Optional<List<Catalog>> combined = queryEngine.executeCombinedQuery(qr);
        recordStep(tracker, "path-a.combined.query");

        if (combined.isEmpty()) {
            // Engine could not build spatial conditions → fall back to parallel
            log.info("discovery.path=A.fallback reason=no-spatial-conditions transactionId={}",
                    qr.transactionId());
            return pathAParallel(qr, context, tracker);
        }

        // combined.get() may be an empty list — that is a valid "no results" response
        List<Catalog> processed = catalogPipeline.process(combined.get(), qr);
        recordStep(tracker, "path-a.pipeline");

        return buildResponse(processed, context);
    }

    /**
     * Fallback for Path A: runs filter and spatial queries concurrently,
     * then intersects results by item ID in Java.
     */
    private DiscoverResponse pathAParallel(QueryRequest qr, Context context, LatencyTracker tracker)
            throws Exception {

        Map<String, String> mdcCopy = MDC.getCopyOfContextMap();
        int timeoutSec = properties.getPostgresql().getParallelQueryTimeoutSeconds();

        CompletableFuture<List<Catalog>> filterFuture = CompletableFuture.supplyAsync(() -> {
            restoreMDC(mdcCopy);
            try {
                return queryEngine.executeFilterQuery(qr);
            } catch (Exception e) {
                throw new CompletionException("filter-query failed", e);
            } finally {
                MDC.clear();
            }
        }, queryExecutor);

        CompletableFuture<List<Catalog>> spatialFuture = CompletableFuture.supplyAsync(() -> {
            restoreMDC(mdcCopy);
            try {
                return queryEngine.executeSpatialQuery(qr);
            } catch (Exception e) {
                throw new CompletionException("spatial-query failed", e);
            } finally {
                MDC.clear();
            }
        }, queryExecutor);

        try {
            CompletableFuture.allOf(filterFuture, spatialFuture).get(timeoutSec, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("discovery.path=A.parallel.timeout transactionId={} timeoutSec={}",
                    qr.transactionId(), timeoutSec, e);
            filterFuture.cancel(true);
            spatialFuture.cancel(true);
            throw new Exception("Parallel queries timed out after " + timeoutSec + "s", e);
        }

        List<Catalog> filterResult  = filterFuture.join();
        List<Catalog> spatialResult = spatialFuture.join();
        recordStep(tracker, "path-a.parallel.queries");

        log.info("discovery.path=A.parallel filterCatalogs={} spatialCatalogs={} transactionId={}",
                filterResult.size(), spatialResult.size(), qr.transactionId());

        List<Catalog> intersected = intersectByItemId(filterResult, spatialResult, qr.transactionId());
        recordStep(tracker, "path-a.parallel.intersect");

        if (intersected.isEmpty()) {
            return responseProcessor.buildEmptyResponse(context);
        }

        List<Catalog> processed = catalogPipeline.process(intersected, qr);
        recordStep(tracker, "path-a.parallel.pipeline");

        return buildResponse(processed, context);
    }

    // ── Path B: filter only ───────────────────────────────────────────────────

    private DiscoverResponse pathB(QueryRequest qr, Context context, LatencyTracker tracker)
            throws Exception {
        List<Catalog> catalogs = queryEngine.executeFilterQuery(qr);
        recordStep(tracker, "path-b.query");

        List<Catalog> processed = catalogPipeline.process(catalogs, qr);
        recordStep(tracker, "path-b.pipeline");

        return buildResponse(processed, context);
    }

    // ── Path C: spatial only ──────────────────────────────────────────────────

    private DiscoverResponse pathC(QueryRequest qr, Context context, LatencyTracker tracker)
            throws Exception {
        List<Catalog> catalogs = queryEngine.executeSpatialQuery(qr);
        recordStep(tracker, "path-c.query");

        List<Catalog> processed = catalogPipeline.process(catalogs, qr);
        recordStep(tracker, "path-c.pipeline");

        return buildResponse(processed, context);
    }

    // ── Path D: text search ────────────────────────────────────────────────────

    /**
     * Runs the text-search engine on the dedicated I/O executor so the blocking
     * HTTP call inside {@code NLWebService.queryNLWeb()} does not tie up servlet
     * threads under concurrent load.
     */
    private DiscoverResponse pathD(QueryRequest qr, Context context, LatencyTracker tracker)
            throws Exception {
        Map<String, String> mdcCopy = MDC.getCopyOfContextMap();
        int timeoutSec = properties.getPostgresql().getParallelQueryTimeoutSeconds();

        CompletableFuture<List<Catalog>> searchFuture = CompletableFuture.supplyAsync(() -> {
            restoreMDC(mdcCopy);
            try {
                return textSearchEngine.search(qr.textSearch(), qr);
            } catch (Exception e) {
                throw new CompletionException("text-search failed", e);
            } finally {
                MDC.clear();
            }
        }, queryExecutor);

        List<Catalog> catalogs;
        try {
            catalogs = searchFuture.get(timeoutSec, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            searchFuture.cancel(true);
            log.error("discovery.path=D.timeout transactionId={} timeoutSec={}", qr.transactionId(), timeoutSec);
            throw new Exception("Text search timed out after " + timeoutSec + "s", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new Exception("Text search failed: " + cause.getMessage(), cause);
        }

        recordStep(tracker, "path-d.search");

        List<Catalog> processed = catalogPipeline.process(catalogs, qr);
        recordStep(tracker, "path-d.pipeline");

        return buildResponse(processed, context);
    }

    // ── Intersection (Path A parallel fallback) ───────────────────────────────

    /**
     * Intersects two catalog lists by item ID.  Retains catalogs / items from
     * {@code filterResult} whose item IDs also appear in {@code spatialResult}.
     * Filter-result catalogs carry {@code matching_offers} data and therefore
     * take precedence.
     */
    private List<Catalog> intersectByItemId(
            List<Catalog> filterResult,
            List<Catalog> spatialResult,
            String transactionId) {

        if (filterResult.isEmpty() || spatialResult.isEmpty()) {
            log.info("discovery.intersect.empty transactionId={}", transactionId);
            return List.of();
        }

        Set<String> spatialItemIds = spatialResult.stream()
                .filter(c -> c.getItems() != null)
                .flatMap(c -> c.getItems().stream())
                .map(item -> item.getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<Catalog> intersected = filterResult.stream()
                .filter(catalog -> catalog.getItems() != null)
                .map(catalog -> {
                    List<org.beckn.discover.model.Item> matchingItems = catalog.getItems().stream()
                            .filter(item -> item.getId() != null && spatialItemIds.contains(item.getId()))
                            .collect(Collectors.toList());

                    if (matchingItems.isEmpty()) return null;

                    // Clone the catalog with only the intersecting items
                    Catalog narrowed = shallowCopyCatalog(catalog);
                    narrowed.setItems(matchingItems);
                    return narrowed;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        log.info("discovery.intersect.done filterCatalogs={} spatialItemIds={} intersectedCatalogs={} transactionId={}",
                filterResult.size(), spatialItemIds.size(), intersected.size(), transactionId);
        return intersected;
    }

    /**
     * Creates a shallow copy of a catalog preserving all metadata fields but
     * leaving items / offers as new empty lists (caller must populate them).
     */
    private static Catalog shallowCopyCatalog(Catalog src) {
        Catalog copy = new Catalog();
        copy.setId(src.getId());
        copy.setContext(src.getContext());
        copy.setType(src.getType());
        copy.setDescriptor(src.getDescriptor());
        copy.setProviderId(src.getProviderId());
        copy.setBppId(src.getBppId());
        copy.setBppUri(src.getBppUri());
        copy.setValidity(src.getValidity());
        copy.setOffers(src.getOffers() != null ? new java.util.ArrayList<>(src.getOffers()) : new java.util.ArrayList<>());
        copy.setItems(new java.util.ArrayList<>());
        return copy;
    }

    // ── Response building ─────────────────────────────────────────────────────

    private DiscoverResponse buildResponse(List<Catalog> processed, Context context) {
        if (processed.isEmpty()) {
            return responseProcessor.buildEmptyResponse(context);
        }
        return responseProcessor.buildResponse(processed, context);
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private static void validateRequest(DiscoverRequest request) {
        Objects.requireNonNull(request, "DiscoverRequest must not be null");
        Objects.requireNonNull(request.getContext(), "DiscoverRequest.context must not be null");
    }

    // ── MDC management ────────────────────────────────────────────────────────

    private static void setupMDC(Context context) {
        if (context.getTransactionId() != null) MDC.put("transactionId", context.getTransactionId());
        if (context.getMessageId()    != null) MDC.put("messageId",     context.getMessageId());
        if (context.getBapId()        != null) MDC.put("bapId",         context.getBapId());
    }

    private static void restoreMDC(Map<String, String> snapshot) {
        if (snapshot != null) MDC.setContextMap(snapshot);
    }

    private static void clearMDC() {
        MDC.clear();
    }

    // ── Latency tracking ──────────────────────────────────────────────────────

    private static void recordStep(LatencyTracker tracker, String step) {
        if (tracker != null) tracker.recordStep(step);
    }
}
