package org.beckn.discover.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
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
    private final Timer   processingTimer;

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
        this.processingTimer = Timer.builder("discovr.discover.processing.duration")
                .description("Discovery request processing duration")
                .register(meterRegistry);

        this.searchTimers = Map.of(
                ENGINE_POSTGRES,      Timer.builder("discovr.discover.search.duration").tag("engine", ENGINE_POSTGRES).register(meterRegistry),
                ENGINE_ELASTICSEARCH, Timer.builder("discovr.discover.search.duration").tag("engine", ENGINE_ELASTICSEARCH).register(meterRegistry),
                ENGINE_NLWEB,         Timer.builder("discovr.discover.search.duration").tag("engine", ENGINE_NLWEB).register(meterRegistry)
        );
        this.resultSummaries = Map.of(
                ENGINE_POSTGRES,      DistributionSummary.builder("discovr.discover.results.count").tag("engine", ENGINE_POSTGRES).register(meterRegistry),
                ENGINE_ELASTICSEARCH, DistributionSummary.builder("discovr.discover.results.count").tag("engine", ENGINE_ELASTICSEARCH).register(meterRegistry),
                ENGINE_NLWEB,         DistributionSummary.builder("discovr.discover.results.count").tag("engine", ENGINE_NLWEB).register(meterRegistry)
        );
    }

    // ── Recording ────────────────────────────────────────────────────────────

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
        log.error("discovery.metrics.failure durationMs={} transactionId={} error={}",
                ms, transactionId, e.getMessage(), e);
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
            log.warn("discovery.metrics.unknown_engine engine={}", engine);
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
            log.warn("discovery.metrics.unknown_engine engine={}", engine);
        }
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
        log.info("discovery.metrics.reset");
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
