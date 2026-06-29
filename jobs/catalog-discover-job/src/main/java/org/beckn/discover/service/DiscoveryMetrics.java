package org.beckn.discover.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.beckn.discover.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static net.logstash.logback.argument.StructuredArguments.value;

/**
 * Thread-safe request metrics: Micrometer meters for Prometheus observability
 * plus {@link AtomicLong} counters that support the admin {@link #resetStats()} operation.
 *
 * <h3>Dual tracking rationale</h3>
 * <ul>
 *   <li><b>Micrometer {@link Counter}/{@link Timer}</b> — auto-exported to
 *       {@code /actuator/prometheus}; cumulative and monotonically increasing (Prometheus semantics).</li>
 *   <li><b>{@link AtomicLong} counters</b> — support the operational {@link #resetStats()} endpoint;
 *       Prometheus counters cannot be reset.</li>
 * </ul>
 *
 * <h3>Metrics exposed via Prometheus</h3>
 * <ul>
 *   <li>{@code discovery_requests_total} — every request received</li>
 *   <li>{@code discovery_requests_success} — successful requests</li>
 *   <li>{@code discovery_requests_failure} — failed requests</li>
 *   <li>{@code discovery_processing_duration_seconds} — {@link Timer} histogram (successful requests)</li>
 *   <li>{@code discovery_search_duration_seconds} — per-engine search latency</li>
 *   <li>{@code discovery_results_count} — per-engine result count distribution</li>
 * </ul>
 */
@Component
public class DiscoveryMetrics {

    private static final Logger log     = LoggerFactory.getLogger(DiscoveryMetrics.class);
    private static final Logger perfLog = LoggerFactory.getLogger("org.beckn.discover.performance");

    private static final String ENGINE_POSTGRES      = "postgres";
    private static final String ENGINE_ELASTICSEARCH = "elasticsearch";
    private static final String ENGINE_NLWEB         = "nlweb";

    // ── Micrometer meters (auto-exported to /actuator/prometheus) ─────────────

    private final Counter totalRequestsCounter;
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter schemaFilterAppliedCounter;
    private final Timer   processingTimer;

    // ── Chain routing meters ─────────────────────────────────────────────────
    private final Counter chainEmptyResultsCounter;
    private final Counter chainTruncatedByCapCounter;
    private final Counter chainUnderreturnCounter;
    private final Counter chainFallbackNoEsCounter;
    private final DistributionSummary esResourceIdsCountSummary;
    private final DistributionSummary psqlAllowlistSizeSummary;
    private final Map<String, Counter> routeSelectedCounters;
    private final Map<String, Timer>   routeLatencyTimers;

    // ── Overall (non-engine-tagged) result count ─────────────────────────────

    private static final String METRIC_RESULT_COUNT_TOTAL = "discovr.discover.results.count.total";

    private final DistributionSummary resultCountTotal;

    // Pre-registered per-engine meters (avoids per-call registration overhead)
    private final Map<String, Timer>               searchTimers;
    private final Map<String, DistributionSummary> resultSummaries;

    // ── AtomicLong counters (support admin resetStats()) ─────────────────────

    private final AtomicLong totalRequests       = new AtomicLong();
    private final AtomicLong successfulRequests  = new AtomicLong();
    private final AtomicLong failedRequests      = new AtomicLong();
    private final AtomicLong totalProcessingTime = new AtomicLong();

    public DiscoveryMetrics(MeterRegistry meterRegistry) {
        this.totalRequestsCounter = Counter.builder("discovr.discover.requests.total")
                .description("Total discovery requests received")
                .register(meterRegistry);
        this.successCounter = Counter.builder("discovr.discover.requests.success")
                .description("Successful discovery requests")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("discovr.discover.requests.failure")
                .description("Failed discovery requests")
                .register(meterRegistry);
        this.schemaFilterAppliedCounter = Counter.builder("discovr.discover.schema_filter.applied")
                .description("Number of times schema context filtering was applied in ES queries")
                .register(meterRegistry);
        this.processingTimer = Timer.builder("discovr.discover.processing.duration")
                .description("Discovery request processing duration")
                .register(meterRegistry);
        this.resultCountTotal = DistributionSummary.builder(METRIC_RESULT_COUNT_TOTAL)
                .description("Number of catalog results returned per discover query")
                .register(meterRegistry);

        this.searchTimers = Map.of(
                ENGINE_POSTGRES,      buildSearchDurationTimer(ENGINE_POSTGRES, meterRegistry),
                ENGINE_ELASTICSEARCH, buildSearchDurationTimer(ENGINE_ELASTICSEARCH, meterRegistry),
                ENGINE_NLWEB,         buildSearchDurationTimer(ENGINE_NLWEB, meterRegistry)
        );
        this.resultSummaries = Map.of(
                ENGINE_POSTGRES,      DistributionSummary.builder("discovr.discover.results.count").tag("engine", ENGINE_POSTGRES).register(meterRegistry),
                ENGINE_ELASTICSEARCH, DistributionSummary.builder("discovr.discover.results.count").tag("engine", ENGINE_ELASTICSEARCH).register(meterRegistry),
                ENGINE_NLWEB,         DistributionSummary.builder("discovr.discover.results.count").tag("engine", ENGINE_NLWEB).register(meterRegistry)
        );

        // Chain routing meters
        this.chainEmptyResultsCounter = Counter.builder("discovr.discover.chain.empty_results.total")
                .description("Number of chain requests that returned 0 results")
                .register(meterRegistry);
        this.chainTruncatedByCapCounter = Counter.builder("discovr.discover.chain.truncated_by_cap.total")
                .description("Number of times the ES candidate list was truncated by max-ids cap")
                .register(meterRegistry);
        this.chainUnderreturnCounter = Counter.builder("discovr.discover.chain.underreturn.total")
                .description("Number of times PSQL returned fewer rows than the request limit after IN-filter")
                .register(meterRegistry);
        this.chainFallbackNoEsCounter = Counter.builder("discovr.discover.chain.fallback_no_es.total")
                .description("Number of JSONPath+text requests that fell back to PSQL-only because ES engine was absent")
                .register(meterRegistry);
        this.esResourceIdsCountSummary = DistributionSummary.builder("discovr.discover.chain.es_resource_ids_count")
                .description("Size of the resource-id list returned by ES step 1 of the chain")
                .register(meterRegistry);
        this.psqlAllowlistSizeSummary = DistributionSummary.builder("discovr.discover.chain.psql_allowlist_size")
                .description("Size of the PSQL allowlist after ES step 1")
                .register(meterRegistry);

        List<String> routePaths = List.of("A", "B", "C", "D", "chain");
        this.routeSelectedCounters = new HashMap<>();
        this.routeLatencyTimers    = new HashMap<>();
        for (String path : routePaths) {
            this.routeSelectedCounters.put(path,
                    Counter.builder("discovr.discover.route_selected.total")
                            .tag("path", path)
                            .description("Number of requests routed to each query path")
                            .register(meterRegistry));
            this.routeLatencyTimers.put(path,
                    Timer.builder("discovr.discover.route.latency")
                            .tag("path", path)
                            .description("Per-path query latency")
                            .register(meterRegistry));
        }
    }

    private static Timer buildSearchDurationTimer(String engine, MeterRegistry registry) {
        return Timer.builder("discovr.discover.search.duration")
                .tag("engine", engine)
                .description("ES/PG/NLWeb search latency per engine")
                .serviceLevelObjectives(
                        Duration.ofMillis(10),
                        Duration.ofMillis(50),
                        Duration.ofMillis(100),
                        Duration.ofMillis(200),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30))
                .register(registry);
    }

    // ── Recording ────────────────────────────────────────────────────────────

    /**
     * Increments the schema filter applied counter.
     * Call when a request carries schemaContext URLs that are pushed down into ES.
     */
    public void incrementSchemaFilterApplied() {
        schemaFilterAppliedCounter.increment();
    }

    /** Increments the total request counter. Call at the start of each request. */
    public void incrementTotalRequests() {
        totalRequests.incrementAndGet();
        totalRequestsCounter.increment();
    }

    /**
     * Records a successful request: increments success counter, accumulates
     * end-to-end duration, and records a Micrometer timer sample.
     */
    public void recordSuccess(Instant startTime) {
        long ms = Duration.between(startTime, Instant.now()).toMillis();
        totalProcessingTime.addAndGet(ms);
        successfulRequests.incrementAndGet();
        successCounter.increment();
        processingTimer.record(Duration.ofMillis(ms));
        perfLog.info("{}", value("event", "discovery.metrics.success"),
                value("durationMs", ms),
                value("successTotal", successfulRequests.get()),
                value("failTotal", failedRequests.get()));
    }

    /**
     * Records a failed request: increments failure counter.
     * Does <em>not</em> add to {@code totalProcessingTime} — keeping the average
     * meaningful for successful requests only.
     */
    public void recordFailure(Instant startTime, Exception e, String transactionId) {
        long ms = Duration.between(startTime, Instant.now()).toMillis();
        failedRequests.incrementAndGet();
        failureCounter.increment();
        log.error("event={} durationMs={} error={}",
                LogEvent.METRICS_FAILURE, ms, e.getMessage(), e);
    }

    /**
     * Records the duration of a single engine search call.
     *
     * @param engine one of {@code "postgres"}, {@code "elasticsearch"}, {@code "nlweb"}
     * @param duration elapsed time for the search call
     */
    public void recordSearchDuration(String engine, Duration duration) {
        Timer timer = searchTimers.get(engine);
        if (timer != null) {
            timer.record(duration);
        } else {
            log.warn("event={} engine={}", LogEvent.METRICS_UNKNOWN_ENGINE, engine);
        }
    }

    /**
     * Records the number of catalog results returned by a search call.
     *
     * @param engine      one of {@code "postgres"}, {@code "elasticsearch"}, {@code "nlweb"}
     * @param resultCount number of catalogs returned
     */
    public void recordResultCount(String engine, int resultCount) {
        DistributionSummary summary = resultSummaries.get(engine);
        if (summary != null) {
            summary.record(resultCount);
        } else {
            log.warn("event={} engine={}", LogEvent.METRICS_UNKNOWN_ENGINE, engine);
        }
    }

    /**
     * Records the total number of catalog results returned by a discover query,
     * independent of which engine served the request.
     *
     * @param count number of catalogs included in the response
     */
    public void recordResultCount(int count) {
        resultCountTotal.record(count);
    }

    // ── Chain routing recording ───────────────────────────────────────────────

    /** Increments the chain-empty-results counter. */
    public void incrementChainEmptyResults() {
        chainEmptyResultsCounter.increment();
    }

    /** Increments the chain-truncated-by-cap counter. */
    public void incrementChainTruncatedByCap() {
        chainTruncatedByCapCounter.increment();
    }

    /**
     * Increments the chain-underreturn counter.
     * Call when PSQL chain step returns fewer rows than the request limit.
     */
    public void incrementChainUnderreturn() {
        chainUnderreturnCounter.increment();
    }

    /**
     * Increments the chain-fallback-no-ES counter.
     * Call when a JSONPath+text request degrades to PSQL-only because the
     * Elasticsearch engine bean is absent (e.g. discovery.spatial.engine=postgresql).
     */
    public void incrementChainFallbackNoEs() {
        chainFallbackNoEsCounter.increment();
    }

    /**
     * Records the count of resource IDs returned by ES step 1 of the chain.
     *
     * @param count number of resource IDs returned by ES (the matching set passed to PSQL)
     */
    public void recordEsResourceIdsCount(int count) {
        esResourceIdsCountSummary.record(count);
    }

    /**
     * Records the size of the PSQL allowlist passed to chain step 2.
     *
     * @param size number of IDs in the allowlist
     */
    public void recordPsqlAllowlistSize(int size) {
        psqlAllowlistSizeSummary.record(size);
    }

    /**
     * Increments the route-selected counter for the given path label.
     *
     * @param path one of {@code A}, {@code B}, {@code C}, {@code D}, {@code chain}
     */
    public void incrementRouteSelected(String path) {
        Counter c = routeSelectedCounters.get(path);
        if (c != null) c.increment();
    }

    /**
     * Records latency for the given query path.
     *
     * @param path     one of {@code A}, {@code B}, {@code C}, {@code D}, {@code chain}
     * @param duration elapsed time
     */
    public void recordRouteLatency(String path, Duration duration) {
        Timer t = routeLatencyTimers.get(path);
        if (t != null) t.record(duration);
    }

    // ── Stats retrieval ───────────────────────────────────────────────────────

    /** Returns an immutable snapshot of the current processing statistics. */
    public ProcessingStats getProcessingStats() {
        long success = successfulRequests.get();
        long total   = totalRequests.get();
        long totalMs = totalProcessingTime.get();
        double avgMs = success > 0 ? (double) totalMs / success : 0.0;
        return new ProcessingStats(total, success, failedRequests.get(), totalMs, avgMs);
    }

    /** Resets the admin counters to zero. Micrometer meters continue to accumulate. */
    public void resetStats() {
        totalRequests.set(0);
        successfulRequests.set(0);
        failedRequests.set(0);
        totalProcessingTime.set(0);
        log.info("event={}", LogEvent.METRICS_RESET);
    }

    /** Always returns {@code true}; extend with circuit-breaker state if needed. */
    public boolean isHealthy() {
        return true;
    }

    // ── Stats record ─────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of processing statistics.
     *
     * @param totalRequests           total requests received since last reset
     * @param successfulRequests      requests that completed without exception
     * @param failedRequests          requests that threw an exception
     * @param totalProcessingTimeMs   accumulated duration across successful requests
     * @param averageProcessingTimeMs {@code totalProcessingTimeMs / successfulRequests}
     */
    public record ProcessingStats(
            long   totalRequests,
            long   successfulRequests,
            long   failedRequests,
            long   totalProcessingTimeMs,
            double averageProcessingTimeMs) {

        public double successRate() {
            return totalRequests > 0 ? (double) successfulRequests / totalRequests * 100 : 0.0;
        }

        public double failureRate() {
            return totalRequests > 0 ? (double) failedRequests / totalRequests * 100 : 0.0;
        }
    }
}
