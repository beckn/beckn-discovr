package org.beckn.discover.service;

import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.exception.SemanticSearchException;
import org.beckn.discover.logging.BecknMdcContext;
import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.logging.LogMessages;
import org.beckn.discover.model.Catalog;
import org.beckn.discover.model.Context;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.model.DiscoverResponse;
import org.beckn.discover.service.elasticsearch.ElasticsearchQueryEngine;
import org.beckn.discover.service.engine.QueryEngine;
import org.beckn.discover.service.engine.QueryRequest;
import org.beckn.discover.service.engine.TextSearchEngine;
import org.beckn.discover.service.postgresql.PostgreSQLQueryEngine;
import org.beckn.discover.service.postgresql.ProviderOfferEnricher;
import org.beckn.discover.service.response.CatalogPipeline;
import org.beckn.discover.service.response.ResponseProcessor;
import org.beckn.discover.util.LatencyTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import static net.logstash.logback.argument.StructuredArguments.value;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

/**
 * Main orchestration service for discovery request processing.
 *
 * <h3>Query routing — 7 J/G/T cases</h3>
 * <pre>
 * ┌───┬───┬───┬───┬───────────┬──────────────────────────────────────────────┐
 * │ # │ J │ G │ T │   Route   │                 Engine flow                  │
 * ├───┼───┼───┼───┼───────────┼──────────────────────────────────────────────┤
 * │ 1 │ ✓ │   │   │ Path B    │ PSQL JSONPath                                │
 * │ 2 │   │ ✓ │   │ Path C    │ PSQL + PostGIS                               │
 * │ 3 │   │   │ ✓ │ Path D    │ ES text (BM25 / semantic / NLWeb)            │
 * │ 4 │ ✓ │ ✓ │   │ Path A    │ PSQL + PostGIS combined (one query)          │
 * │ 5 │   │ ✓ │ ✓ │ Path C    │ ES (text + geo_shape)                        │
 * │ 6 │ ✓ │   │ ✓ │ JSONPath  │ ES text → IDs → PSQL JSONPath + IN           │
 * │   │   │   │   │  + text   │                                              │
 * │ 7 │ ✓ │ ✓ │ ✓ │ JSONPath  │ ES text+geo → IDs → PSQL JSONPath + geo + IN │
 * │   │   │   │   │  + text   │                                              │
 * └───┴───┴───┴───┴───────────┴──────────────────────────────────────────────┘
 *   J = JSONPath filters    G = spatial (geo)    T = text or semantic search
 * </pre>
 *
 * <h3>Post-processing</h3>
 * After every route the raw {@code List<Catalog>} passes through
 * {@link CatalogPipeline} (schema filter → dedup offers → cross-filter items /
 * offers → remove empty catalogs) before the response is assembled.
 *
 * <h3>Semantic search</h3>
 * When {@code discovery.text-search.engine=els-semantic-search} (i.e. an
 * {@link org.beckn.discover.service.elasticsearch.EmbeddingClient} bean is present),
 * the semantic (KNN) path is used everywhere a text condition is involved:
 * <ul>
 *   <li>Case 3 (T-only) — via the {@link TextSearchEngine} bean
 *       ({@code ElasticsearchTextSearchEngine#search}).</li>
 *   <li>Case 5 (G+T) — via {@code ElasticsearchQueryEngine.executeSpatialQuery},
 *       which runs KNN with geo_shape as {@code knn.filter}.</li>
 *   <li>Cases 6 (J+T) and 7 (J+G+T) — via
 *       {@code ElasticsearchQueryEngine.fetchMatchingResourceIds}, which runs KNN
 *       restricted to {@code _source: [resource_id]} for the chain's step 1.</li>
 * </ul>
 * When the embedding client is absent (e.g. {@code native-els}), all of the above
 * use lexical BM25 instead. Semantic internals (enrich + embed) live in
 * {@code QueryEnricher} / {@code EmbeddingClient} and are reused unchanged across
 * every path — not re-implemented per route.
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li>Constructor injection — all fields are {@code final} (AR-3.1).</li>
 *   <li>Case 4 (J+G) always routes through
 *       {@link PostgreSQLQueryEngine#executeCombinedQuery}, regardless of
 *       {@code discovery.spatial.engine} config. If spatial conditions cannot
 *       be built, an {@link IllegalStateException} is thrown — there is no
 *       parallel fallback.</li>
 *   <li>Cases 6 and 7 require the ES engine bean. When ES is absent, the route
 *       degrades to case 4 (J+G) or case 1 (J only), dropping the text condition.</li>
 *   <li>MDC is captured before spawning async futures so that all threads carry
 *       the same transaction / message / BAP identifiers (NFR-3.3).</li>
 *   <li>Async queries use a dedicated I/O thread pool ({@code discoveryQueryExecutor}),
 *       not the JVM ForkJoinPool common pool.</li>
 * </ul>
 */
@Service
public class DiscoveryService {

    private static final Logger log     = LoggerFactory.getLogger(DiscoveryService.class);
    private static final Logger perfLog = LoggerFactory.getLogger("org.beckn.discover.performance");

    private final QueryEngine             queryEngine;
    private final TextSearchEngine        textSearchEngine;
    private final CatalogPipeline         catalogPipeline;
    private final ResponseProcessor       responseProcessor;
    private final ProviderOfferEnricher   providerOfferEnricher;
    private final DiscoveryMetrics        metrics;
    private final DiscoveryProperties     properties;
    private final ExecutorService         queryExecutor;
    // Chain-specific engines — present only when the relevant @ConditionalOnProperty fires.
    private final Optional<ElasticsearchQueryEngine> esQueryEngine;
    private final PostgreSQLQueryEngine   pgQueryEngine;

    public DiscoveryService(
            QueryEngine                                queryEngine,
            TextSearchEngine                           textSearchEngine,
            CatalogPipeline                            catalogPipeline,
            ResponseProcessor                          responseProcessor,
            ProviderOfferEnricher                      providerOfferEnricher,
            DiscoveryMetrics                           metrics,
            DiscoveryProperties                        properties,
            @Qualifier("discoveryQueryExecutor") ExecutorService queryExecutor,
            Optional<ElasticsearchQueryEngine>         esQueryEngine,
            PostgreSQLQueryEngine                      pgQueryEngine) {
        this.queryEngine          = queryEngine;
        this.textSearchEngine     = textSearchEngine;
        this.catalogPipeline      = catalogPipeline;
        this.responseProcessor    = responseProcessor;
        this.providerOfferEnricher = providerOfferEnricher;
        this.metrics              = metrics;
        this.properties           = properties;
        this.queryExecutor        = queryExecutor;
        this.esQueryEngine        = esQueryEngine;
        this.pgQueryEngine        = pgQueryEngine;
    }

    // ── Public entry points ──────────────────────────────────────────────────

    /**
     * Synchronous entry point.  Validates, sets up MDC, routes to the correct
     * query path, applies the pipeline, and assembles the response.
     */
    public DiscoverResponse processDiscoveryRequest(DiscoverRequest request) {
        return processDiscoveryRequest(request, null, null);
    }

    /**
     * Synchronous entry point carrying the {@code ?active}/{@code ?validity} value-match flags.
     * Each is a nullable {@link Boolean}: a non-null value is the caller's per-request override;
     * {@code null} means "not supplied", in which case the {@code discovery.filter.activeCatalog}
     * / {@code discovery.filter.validCatalogs} config default is applied. The resolved values are
     * passed to the query engines, which filter in-query (before LIMIT), independently of network
     * scoping. Value-match: {@code true} → only active / currently-valid; {@code false} → only
     * inactive / not-currently-valid.
     */
    public DiscoverResponse processDiscoveryRequest(DiscoverRequest request, Boolean active, Boolean validity) {
        validateRequest(request);
        setupMDC(request.getContext());
        metrics.incrementTotalRequests();

        // Resolve each dimension: explicit query param wins; otherwise fall back to config default.
        Boolean effectiveActive   = active   != null ? active   : properties.getFilter().isActiveCatalog();
        Boolean effectiveValidity = validity != null ? validity : properties.getFilter().isValidCatalogs();

        Instant start = Instant.now();
        LatencyTracker tracker = properties.isLatencyTrackingEnabled() ? new LatencyTracker() : null;

        try {
            log.info(LogEvent.QUERY_STARTED);

            QueryRequest qr = QueryRequest.from(request, effectiveActive, effectiveValidity);

            // Track schema filter metric when ES path will apply schema push-down
            if (qr.hasSchemaFilters() && textSearchEngine.appliesSchemaFilter()) {
                metrics.incrementSchemaFilterApplied();
            }

            DiscoverResponse response = route(qr, request.getContext(), tracker);

            long ms = Duration.between(start, Instant.now()).toMillis();
            metrics.recordSuccess(start);
            log.info(LogEvent.QUERY_COMPLETED,
                    value("durationMs", ms));
            perfLog.info(LogEvent.QUERY_COMPLETED,
                    value("durationMs", ms));

            return response;

        } catch (IllegalArgumentException e) {
            // Client-side validation failure surfaced mid-query — e.g. an unsupported/blank spatial
            // operation that passed schema validation but the PostGIS builder cannot honour for a
            // J+G request (case 4). Propagate as-is so GlobalExceptionHandler maps it to 400 SCH_;
            // wrapping it in the generic RuntimeException below would mask it as a 500 server fault.
            metrics.recordFailure(start, e, request.getContext().getTransactionId());
            log.warn(LogEvent.QUERY_FAILED,
                    value("error", e.getMessage()));
            throw e;
        } catch (SemanticSearchException e) {
            // Propagate as-is — GlobalExceptionHandler maps this to 500 NET_DOWNSTREAM_UNAVAILABLE
            metrics.recordFailure(start, e, request.getContext().getTransactionId());
            throw e;
        } catch (Exception e) {
            metrics.recordFailure(start, e, request.getContext().getTransactionId());
            log.error(LogEvent.QUERY_FAILED,
                    value("error", e.getMessage()),
                    e);
            throw new RuntimeException("Failed to process discovery request", e);
        } finally {
            if (tracker != null) tracker.logSummary(request.getContext().getTransactionId(),
                    metrics.getProcessingStats().successfulRequests() > 0);
            // MDC lifecycle is owned by the caller (controller finally-block or consumer
            // finally-block). The service must not clear MDC here — doing so would erase
            // correlation fields before the caller's own cleanup and logging run.
        }
    }

    /**
     * Asynchronous entry point.  Runs {@link #processDiscoveryRequest} on the
     * calling thread's MDC context so log correlation is preserved.
     */
    public CompletableFuture<DiscoverResponse> processDiscoveryRequestAsync(DiscoverRequest request) {
        return processDiscoveryRequestAsync(request, null, null);
    }

    /**
     * Asynchronous entry point carrying the {@code ?active}/{@code ?validity} value-match flags,
     * so the async path honors them identically to the synchronous one (nullable ⇒ config default).
     */
    public CompletableFuture<DiscoverResponse> processDiscoveryRequestAsync(
            DiscoverRequest request, Boolean active, Boolean validity) {
        Map<String, String> mdcCopy = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> {
            restoreMDC(mdcCopy);
            try {
                return processDiscoveryRequest(request, active, validity);
            } finally {
                MDC.clear();
            }
        }, queryExecutor);
    }

    // ── Query routing ────────────────────────────────────────────────────────

    private DiscoverResponse route(QueryRequest qr, Context context, LatencyTracker tracker)
            throws Exception {

        // Routing tree — order matters: most specific predicate combination first.
        if (qr.hasFilters() && qr.hasTextSearch()) {
            // Cases 6 (J+T) and 7 (J+G+T): JSONPath+text needs the ES engine for step 1.
            // If ES engine is absent (e.g. discovery.spatial.engine=postgresql), degrade
            // gracefully by dropping the text condition and routing to the JSONPath path
            // (with spatial if present) — returning a silent empty would be worse than
            // honouring the JSONPath (and, when present, spatial) part of the request.
            if (esQueryEngine.isEmpty()) {
                log.warn(LogEvent.CHAIN_ES_ENGINE_ABSENT,
                        value("reason", LogMessages.REASON_CHAIN_FALLBACK_NO_ES),
                        value("hasSpatial", qr.hasSpatial()),
                        value("transactionId", context.getTransactionId()));
                metrics.incrementChainFallbackNoEs();
                if (qr.hasSpatial()) {
                    metrics.incrementRouteSelected("A");
                    return executeJsonPathAndSpatialQuery(qr, context, tracker);
                }
                metrics.incrementRouteSelected("B");
                return executeJsonPathQuery(qr, context, tracker);
            }
            log.info(LogEvent.CHAIN_ROUTE_SELECTED,
                    value("path", LogMessages.PATH_CHAIN),
                    value("hasSpatial", qr.hasSpatial()),
                    value("transactionId", context.getTransactionId()));
            metrics.incrementRouteSelected("chain");
            return executeJsonPathAndTextSearchQuery(qr, context, tracker);
        }
        if (qr.hasFilters() && qr.hasSpatial()) {
            // Case 4 (J+G): single SQL via pgEngine.executeCombinedQuery — regardless of
            // discovery.spatial.engine config (design decision #3).
            // transactionId/messageId are carried by MDC on every line — not repeated here.
            log.info(LogEvent.QUERY_PATH_SELECTED,
                    value("path", LogMessages.PATH_JSONPATH_SPATIAL),
                    value("engine", "postgresql"),
                    value("hasFilters", true),
                    value("hasSpatial", true),
                    value("hasTextSearch", false));
            metrics.incrementRouteSelected("A");
            return executeJsonPathAndSpatialQuery(qr, context, tracker);
        }
        if (qr.hasFilters()) {
            // Case 1 (J only).
            log.info(LogEvent.QUERY_PATH_SELECTED,
                    value("path", LogMessages.PATH_JSONPATH),
                    value("engine", "postgresql"),
                    value("hasFilters", true),
                    value("hasSpatial", false),
                    value("hasTextSearch", false));
            metrics.incrementRouteSelected("B");
            return executeJsonPathQuery(qr, context, tracker);
        }
        if (qr.hasSpatial()) {
            // Cases 2 (G only) and 5 (G+T): spatial engine handles both.
            log.info(LogEvent.QUERY_PATH_SELECTED,
                    value("path", LogMessages.PATH_SPATIAL),
                    value("engine", properties.getSpatial().getEngine()),
                    value("hasFilters", false),
                    value("hasSpatial", true),
                    value("hasTextSearch", qr.hasTextSearch()));
            metrics.incrementRouteSelected("C");
            return executeSpatialOnlyQuery(qr, context, tracker);
        }
        // Case 3 (T only).
        log.info(LogEvent.QUERY_PATH_SELECTED,
                value("path", LogMessages.PATH_TEXT_SEARCH),
                value("engine", properties.getTextSearch().getEngine()),
                value("hasFilters", false),
                value("hasSpatial", false),
                value("hasTextSearch", true));
        metrics.incrementRouteSelected("D");
        return executeTextSearchQuery(qr, context, tracker);
    }

    // ── JSONPath + spatial combined query (case 4 J+G) ────────────────────────

    /**
     * Case 4 (J+G): single-round-trip combined JSONPath + spatial query via PostgreSQL.
     *
     * <p>Always uses {@link PostgreSQLQueryEngine#executeCombinedQuery} directly,
     * regardless of {@code discovery.spatial.engine} config (design decision #3).
     * The ES spatial engine is only for cases 2 and 5.</p>
     *
     * <p>If the engine returns {@code Optional.empty()} (spatial conditions could not
     * be built for a valid J+G request), an {@link IllegalStateException} is thrown.
     * This should not happen for well-formed requests. There is no parallel fallback.</p>
     */
    private DiscoverResponse executeJsonPathAndSpatialQuery(QueryRequest qr, Context context, LatencyTracker tracker)
            throws Exception {

        Instant engineStart = Instant.now();
        // Design decision #3: always use PostgreSQL for case 4, not the routing-configured engine.
        Optional<List<Catalog>> combined = pgQueryEngine.executeCombinedQuery(qr);
        Duration engineDuration = Duration.between(engineStart, Instant.now());
        combined.ifPresent(catalogs -> {
            metrics.recordSearchDuration("postgres", engineDuration);
            metrics.recordResultCount("postgres", catalogs.size());
            metrics.recordRouteLatency("A", engineDuration);
        });
        recordStep(tracker, "jsonpath-spatial.query");

        if (combined.isEmpty()) {
            // Spatial conditions could not be built — this means the request carried a blank or
            // unsupported spatial operation that passed schema validation but the PostGIS builder
            // cannot honour. PostgreSQL is the *only* geo enforcement for case 4 (no ES step), so
            // returning rows here would silently ignore the geo constraint — instead reject the
            // request. This is a client-side validation failure (bad spatial op), not a server
            // fault: WARN + IllegalArgumentException so GlobalExceptionHandler returns 400 (SCH_)
            // rather than 500. The dedicated event lets log filters distinguish it from the benign
            // QUERY_PATH_FALLBACK.
            log.warn(LogEvent.QUERY_COMBINED_SPATIAL_BUILD_FAILED,
                    value("reason", LogMessages.REASON_NO_SPATIAL_CONDITIONS),
                    value("transactionId", context.getTransactionId()));
            throw new IllegalArgumentException(
                    "Spatial conditions could not be built for a J+G request (transactionId="
                    + context.getTransactionId() + "). Check that spatial constraints use supported operations.");
        }

        // combined.get() may be an empty list — that is a valid "no results" response.
        // PostgreSQL JSONPath + spatial — schema already filtered in SQL WHERE.
        List<Catalog> processed = catalogPipeline.process(combined.get(), qr, true);
        recordStep(tracker, "jsonpath-spatial.pipeline");

        return buildResponse(processed, context);
    }

    // ── JSONPath + text search query (cases 6 J+T and 7 J+G+T) ───────────────

    /**
     * Executes the two-step JSONPath + text search query used when both JSONPath
     * filters and a text query are present (cases 6 J+T and 7 J+G+T).
     *
     * <ol>
     *   <li>ES step: text [+ geo] query with {@code _source: [resource_id]} only,
     *       size = {@code min(limit * overfetchFactor, maxIds)}. Runs KNN semantic
     *       when {@code discovery.text-search.engine=els-semantic-search} (i.e.
     *       {@code EmbeddingClient} bean present) and lexical BM25 otherwise —
     *       see {@code ElasticsearchQueryEngine.fetchMatchingResourceIds} for the
     *       dual-mode logic. Returns a ranked list of matching resource IDs.</li>
     *   <li>PSQL step: JSONPath query (+ geo redundantly for case 7) restricted
     *       to those resource IDs; {@code ORDER BY array_position} preserves the ES rank.</li>
     * </ol>
     *
     * <p><b>Semantic search:</b> the KNN path mirrors the structure used by
     * {@code ElasticsearchTextSearchEngine} (case 3) and {@code executeSpatialQuery}
     * (case 5) — enrich → embed → KNN with geo/schema as {@code knn.filter}. The
     * semantic internals are delegated unchanged to {@code QueryEnricher} and
     * {@code EmbeddingClient}; this method does not modify the semantic engine,
     * it only uses the same primitives so chain step 1 honours the configured
     * text-search engine for cases 6 and 7.</p>
     *
     * <p>Short-circuits with an empty response when ES returns 0 resource IDs
     * ({@link LogEvent#CHAIN_EMPTY_FROM_ES}).</p>
     */
    private DiscoverResponse executeJsonPathAndTextSearchQuery(QueryRequest qr, Context context,
            LatencyTracker tracker) throws Exception {

        // Chain runs ES step 1 + PSQL step 2 sequentially, so it gets its own
        // (larger) budget rather than the single-query parallel timeout.
        int timeoutSec  = properties.getChain().getTimeoutSeconds();
        int limit       = properties.getPostgresql().getResultLimit();
        int overfetch   = properties.getChain().getOverfetchFactor();
        int maxIds      = properties.getChain().getMaxIds();
        // Use long to avoid int overflow when both factors are large; clamp by maxIds.
        long requested  = (long) limit * (long) overfetch;
        int esSize      = (int) Math.min(requested, (long) maxIds);
        boolean truncated = requested > maxIds;

        if (truncated) {
            log.info(LogEvent.CHAIN_TRUNCATED_BY_CAP,
                    value("requested", requested),
                    value("cap", maxIds),
                    value("transactionId", context.getTransactionId()));
            metrics.incrementChainTruncatedByCap();
        }

        // Run the full pipeline on the dedicated I/O executor with a single timeout
        // covering both ES step 1 and PSQL step 2. Without this guard, a stuck ES
        // call or slow PSQL would tie up the Kafka consumer thread indefinitely.
        Instant pipelineStart = Instant.now();
        final int esSizeFinal = esSize;
        CompletableFuture<DiscoverResponse> pipelineFuture = runAsyncWithMdc(() ->
                runJsonPathAndTextSearchPipeline(qr, context, tracker, esSizeFinal, limit, pipelineStart));

        try {
            return pipelineFuture.get(timeoutSec, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            pipelineFuture.cancel(true);
            log.error(LogEvent.QUERY_TIMEOUT,
                    value("path", LogMessages.PATH_CHAIN),
                    value("timeoutSec", timeoutSec),
                    e);
            throw new Exception("JSONPath+text query timed out after " + timeoutSec + "s", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new Exception("JSONPath+text query failed: " + cause.getMessage(), cause);
        }
    }

    /**
     * Synchronous body of {@link #executeJsonPathAndTextSearchQuery} — executed
     * inside the async wrapper so a single timeout governs the end-to-end
     * ES + PSQL round-trip.
     */
    private DiscoverResponse runJsonPathAndTextSearchPipeline(QueryRequest qr, Context context,
            LatencyTracker tracker, int esSize, int limit, Instant pipelineStart) throws Exception {

        // ── Step 1: ES text [+ geo] → matching resource IDs ────────────────────
        // fetchMatchingResourceIds picks semantic (KNN) when EmbeddingClient is
        // present, lexical (BM25) otherwise. Internal semantic behaviour is the
        // same as case 3 / case 5 — see ElasticsearchQueryEngine for the branch.
        List<String> resourceIds = esQueryEngine
                .orElseThrow(() -> new IllegalStateException(
                        "JSONPath+text route reached without ES engine — routing tree should have fallen back."))
                .fetchMatchingResourceIds(qr, esSize);
        metrics.recordEsResourceIdsCount(resourceIds.size());

        log.info(LogEvent.CHAIN_ES_CANDIDATES_FETCHED,
                value("resourceIdsCount", resourceIds.size()),
                value("esSize", esSize),
                value("transactionId", context.getTransactionId()));
        recordStep(tracker, "jsonpath-text.es");

        if (resourceIds.isEmpty()) {
            metrics.recordRouteLatency("chain", Duration.between(pipelineStart, Instant.now()));
            log.info(LogEvent.CHAIN_EMPTY_FROM_ES,
                    value("transactionId", context.getTransactionId()));
            metrics.incrementChainEmptyResults();
            return responseProcessor.buildEmptyResponse(context);
        }

        metrics.recordPsqlAllowlistSize(resourceIds.size());
        log.info(LogEvent.CHAIN_PSQL_ALLOWLIST_APPLIED,
                value("allowlistSize", resourceIds.size()),
                value("transactionId", context.getTransactionId()));

        // ── Step 2: PSQL JSONPath [+ geo] restricted to those resource IDs ─────
        Instant psqlStart = Instant.now();
        List<Catalog> catalogs = executeJsonPathOnResourceIds(qr, resourceIds);
        metrics.recordSearchDuration("postgres", Duration.between(psqlStart, Instant.now()));
        metrics.recordResultCount("postgres", catalogs.size());
        // End-to-end pipeline latency (ES step 1 + PSQL step 2) — not ES-only.
        metrics.recordRouteLatency("chain", Duration.between(pipelineStart, Instant.now()));

        log.info(LogEvent.CHAIN_PSQL_DONE,
                value("catalogs", catalogs.size()),
                value("transactionId", context.getTransactionId()));
        recordStep(tracker, "jsonpath-text.psql");

        if (catalogs.isEmpty()) {
            log.info(LogEvent.CHAIN_EMPTY_AFTER_PSQL,
                    value("transactionId", context.getTransactionId()));
            metrics.incrementChainEmptyResults();
            return responseProcessor.buildEmptyResponse(context);
        }

        // PSQL applied schema filter in SQL WHERE; ES already applied text+geo.
        List<Catalog> processed = catalogPipeline.process(catalogs, qr, true);
        recordStep(tracker, "jsonpath-text.pipeline");

        // Count underreturn: fewer resources actually returned than the request limit
        // even though the chain had a pre-filtered resource-id list to work from.
        // Counted AFTER the pipeline so it reflects rows actually returned, not the
        // raw pre-prune PSQL row count.
        if (processed.stream().mapToInt(c -> c.getResources() != null ? c.getResources().size() : 0).sum() < limit) {
            metrics.incrementChainUnderreturn();
        }

        return buildResponse(processed, context);
    }

    /**
     * Step 2 helper: runs PSQL JSONPath [+ geo] restricted to the given resource IDs.
     *
     * <p>For case 7 (J+G+T): re-applies geo conditions in PSQL (belt-and-suspenders)
     * even though ES already filtered by geo. Falls back to case-6 style (JSONPath
     * only, no geo) when PSQL spatial conditions cannot be built.</p>
     */
    private List<Catalog> executeJsonPathOnResourceIds(QueryRequest qr,
            List<String> resourceIds) throws Exception {
        if (qr.hasSpatial()) {
            // Case 7: combined JSONPath + spatial restricted to the resource IDs.
            Optional<List<Catalog>> result =
                    pgQueryEngine.executeJsonPathAndSpatialQueryByResourceIds(qr, resourceIds);
            if (result.isPresent()) {
                return result.get();
            }
            // Spatial conditions could not be built — degrade to case-6 style (JSONPath only).
            // Geo was already applied in ES step 1 for the candidate IDs, so this PSQL re-apply is
            // belt-and-suspenders; dropping it does not by itself widen results beyond the ES geo
            // window. WARN (not DEBUG) because a BAP caller has no other visibility into the geo
            // degradation, and it signals a likely malformed/unsupported spatial op worth alerting on.
            log.warn(LogEvent.QUERY_PATH_FALLBACK,
                    value("reason", "jsonpath-text-spatial-build-failed, falling-back-to-jsonpath-only"),
                    value("transactionId", qr.transactionId()));
        }
        // Case 6: JSONPath restricted to the resource IDs (no geo).
        return pgQueryEngine.executeJsonPathQueryByResourceIds(qr, resourceIds);
    }

    // ── JSONPath-only query (case 1 J) ────────────────────────────────────────

    /**
     * Case 1 (J only): JSONPath query via PostgreSQL — no spatial, no text.
     */
    private DiscoverResponse executeJsonPathQuery(QueryRequest qr, Context context, LatencyTracker tracker)
            throws Exception {
        int timeoutSec = properties.getPostgresql().getParallelQueryTimeoutSeconds();
        Instant engineStart = Instant.now();
        CompletableFuture<List<Catalog>> queryFuture = runAsyncWithMdc(() -> {
            List<Catalog> r = queryEngine.executeFilterQuery(qr);
            metrics.recordSearchDuration("postgres", Duration.between(engineStart, Instant.now()));
            metrics.recordResultCount("postgres", r.size());
            return r;
        });

        List<Catalog> catalogs;
        try {
            catalogs = queryFuture.get(timeoutSec, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            queryFuture.cancel(true);
            log.error(LogEvent.QUERY_TIMEOUT,
                    value("path", "B"),
                    value("timeoutSec", timeoutSec),
                    e);
            throw new Exception("JSONPath query timed out after " + timeoutSec + "s", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            throw new Exception("JSONPath query failed: " + cause.getMessage(), cause);
        }

        recordStep(tracker, "jsonpath.query");

        // PostgreSQL JSONPath — schema already filtered in SQL WHERE clause.
        List<Catalog> processed = catalogPipeline.process(catalogs, qr, true);
        recordStep(tracker, "jsonpath.pipeline");

        return buildResponse(processed, context);
    }

    // ── Spatial only ──────────────────────────────────────────────────────────

    private DiscoverResponse executeSpatialOnlyQuery(QueryRequest qr, Context context, LatencyTracker tracker)
            throws Exception {
        int timeoutSec = properties.getPostgresql().getParallelQueryTimeoutSeconds();
        Instant engineStart = Instant.now();
        CompletableFuture<List<Catalog>> queryFuture = runAsyncWithMdc(() -> {
            List<Catalog> r = queryEngine.executeSpatialQuery(qr);
            metrics.recordSearchDuration("postgres", Duration.between(engineStart, Instant.now()));
            metrics.recordResultCount("postgres", r.size());
            return r;
        });

        List<Catalog> catalogs;
        try {
            catalogs = queryFuture.get(timeoutSec, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            queryFuture.cancel(true);
            log.error(LogEvent.QUERY_TIMEOUT,
                    value("path", "C"),
                    value("timeoutSec", timeoutSec),
                    e);
            throw new Exception("Spatial query timed out after " + timeoutSec + "s", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            throw new Exception("Spatial query failed: " + cause.getMessage(), cause);
        }

        recordStep(tracker, "spatial.query");

        // Spatial-only: ES spatial or PostgreSQL spatial — schema filtered in ES knn.filter or SQL.
        List<Catalog> processed = catalogPipeline.process(catalogs, qr, true);
        recordStep(tracker, "spatial.pipeline");

        return buildResponse(processed, context);
    }

    // ── Text-only query — semantic / BM25 / NLWeb dispatch (case 3 T) ─────────

    /**
     * Case 3 (T only): runs the configured text-search engine on the dedicated
     * I/O executor so the blocking HTTP call does not tie up servlet threads
     * under concurrent load.
     *
     * <p>Dispatches through {@link TextSearchEngine#search} — implementation is
     * one of {@code ElasticsearchTextSearchEngine} (semantic KNN or BM25),
     * {@code NLWebTextSearchEngine} (NLWeb HTTP), depending on
     * {@code discovery.text-search.engine}. Cases 6 and 7 also honour the
     * configured text-search engine via
     * {@link org.beckn.discover.service.elasticsearch.ElasticsearchQueryEngine#fetchMatchingResourceIds}
     * — see the class-level Javadoc for the full dual-mode picture.</p>
     */
    private DiscoverResponse executeTextSearchQuery(QueryRequest qr, Context context, LatencyTracker tracker)
            throws Exception {
        int timeoutSec = properties.getPostgresql().getParallelQueryTimeoutSeconds();
        String engine = properties.getTextSearch().getEngine();
        Instant engineStart = Instant.now();
        CompletableFuture<List<Catalog>> searchFuture = runAsyncWithMdc(() -> textSearchEngine.search(qr.textSearch(), qr));

        List<Catalog> catalogs;
        try {
            catalogs = searchFuture.get(timeoutSec, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            searchFuture.cancel(true);
            log.error(LogEvent.QUERY_TIMEOUT,
                    value("path", "D"),
                    value("timeoutSec", timeoutSec),
                    e);
            throw new Exception("Text search timed out after " + timeoutSec + "s", e);
        } catch (java.util.concurrent.ExecutionException e) {
            // Unwrap CompletionException → original cause (e.g. SemanticSearchException)
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof CompletionException && cause.getCause() != null)
                cause = cause.getCause();
            if (cause instanceof SemanticSearchException sse)
                throw sse;
            throw new Exception("Text search failed: " + cause.getMessage(), cause);
        }

        metrics.recordSearchDuration(engine, Duration.between(engineStart, Instant.now()));
        metrics.recordResultCount(engine, catalogs.size());
        recordStep(tracker, "path-d.search");

        // Path D: use appliesSchemaFilter() to decide if pipeline step 1 can be skipped.
        // ElasticsearchTextSearchEngine returns true; NLWebTextSearchEngine returns false.
        List<Catalog> processed = catalogPipeline.process(catalogs, qr, textSearchEngine.appliesSchemaFilter());
        recordStep(tracker, "path-d.pipeline");

        return buildResponse(processed, context);
    }

    // ── Response building ─────────────────────────────────────────────────────

    private DiscoverResponse buildResponse(List<Catalog> processed, Context context) {
        // Provider-level offers: enrich AFTER pipeline so filterOffersByResourceIds never sees them
        providerOfferEnricher.enrich(processed);

        // Drop catalogs with no offers when require-offers flag is enabled
        if (properties.getFilter().isDiscardCatalogsWithoutOffers()) {
            var discarded = processed.stream()
                    .filter(c -> c.getOffers() == null || c.getOffers().isEmpty())
                    .toList();
            if (!discarded.isEmpty()) {
                processed.removeAll(discarded);
                log.info(LogEvent.CATALOG_DISCARDED_NO_OFFERS,
                        value("discardedCount", discarded.size()),
                        value("discardedCatalogIds", discarded.stream()
                                .map(Catalog::getId)
                                .toList()),
                        value("remainingCount", processed.size()));
            }
        }

        metrics.recordResultCount(processed.size());
        return processed.isEmpty()
                ? responseProcessor.buildEmptyResponse(context)
                : responseProcessor.buildResponse(processed, context);
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private static void validateRequest(DiscoverRequest request) {
        Objects.requireNonNull(request, "DiscoverRequest must not be null");
        Objects.requireNonNull(request.getContext(), "DiscoverRequest.context must not be null");
    }

    /**
     * Runs a callable asynchronously on the dedicated query executor with MDC
     * propagation and error handling.
     *
     * <p>Generic over the return type so the same helper serves both single-engine
     * queries (returning {@code List<Catalog>}) and multi-step pipelines (returning
     * a fully built {@code DiscoverResponse}).</p>
     */
    private <T> CompletableFuture<T> runAsyncWithMdc(Callable<T> callable) {
        Map<String, String> mdcCopy = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> {
            restoreMDC(mdcCopy);
            try {
                return callable.call();
            } catch (Exception e) {
                throw new CompletionException(e.getMessage(), e);
            } finally {
                MDC.clear();
            }
        }, queryExecutor);
    }

    // ── MDC management ────────────────────────────────────────────────────────

    private static void setupMDC(Context context) {
        BecknMdcContext.populate(context);
    }

    private static void restoreMDC(Map<String, String> snapshot) {
        if (snapshot != null) MDC.setContextMap(snapshot);
    }

    // ── Latency tracking ──────────────────────────────────────────────────────

    private static void recordStep(LatencyTracker tracker, String step) {
        if (tracker != null) tracker.recordStep(step);
    }
}
