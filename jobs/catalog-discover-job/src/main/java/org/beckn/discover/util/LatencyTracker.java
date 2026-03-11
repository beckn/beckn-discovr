package org.beckn.discover.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Utility to capture latency for sequential steps and provide a concise summary.
 * Thread-safe for single-request usage.
 */
public class LatencyTracker {

    private static final Logger logger = LoggerFactory.getLogger(LatencyTracker.class);
    private static final Logger performanceLogger = LoggerFactory.getLogger("org.beckn.discover.performance");

    private final Instant startTime;
    private Instant lastCheckpoint;
    private final Map<String, Long> stepDurations = new LinkedHashMap<>();

    public LatencyTracker() {
        this.startTime = Instant.now();
        this.lastCheckpoint = this.startTime;
    }

    /**
     * Records the time elapsed since the previous checkpoint against the provided
     * step name.
     *
     * @param stepName descriptive name of the completed step
     * @return duration in milliseconds recorded for the step
     */
    public long recordStep(String stepName) {
        Instant now = Instant.now();
        long duration = Duration.between(lastCheckpoint, now).toMillis();
        stepDurations.merge(stepName, duration, Long::sum);
        lastCheckpoint = now;
        return duration;
    }

    /**
     * @return total elapsed time since tracker creation in milliseconds
     */
    public long totalElapsedMillis() {
        return Duration.between(startTime, Instant.now()).toMillis();
    }

    /**
     * @return formatted summary string of the captured step durations in
     *         insertion order
     */
    public String formatSummary() {
        if (stepDurations.isEmpty()) {
            return "no checkpoints recorded";
        }

        StringJoiner joiner = new StringJoiner(", ");
        stepDurations.forEach((step, duration) -> joiner.add(step + "=" + duration + "ms"));
        return joiner.toString();
    }

    /**
     * Logs the full latency breakdown for a completed discovery flow.
     *
     * @param transactionId the transaction identifier
     * @param success       whether the flow completed successfully
     */
    public void logSummary(String transactionId, boolean success) {
        long total = totalElapsedMillis();
        String breakdown = formatSummary();
        String status = success ? "SUCCESS" : "FAILED";
        logger.info("Discovery flow completed - TransactionId: {}, Status: {}, TotalDuration: {}ms, Breakdown: {}",
                transactionId, status, total, breakdown);
        performanceLogger.info("Latency breakdown: transaction={} status={} total={}ms steps=[{}]",
                transactionId, status, total, breakdown);
    }
}

