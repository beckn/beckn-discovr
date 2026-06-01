package org.beckn.discover.controller;

import org.beckn.discover.service.DiscoveryMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Admin endpoint for resetting operational metrics.
 *
 * <p>Health checks and detailed stats are served by Spring Boot Actuator:</p>
 * <ul>
 *   <li>{@code GET /actuator/health} — liveness + {@link org.beckn.discover.service.DiscoveryHealthIndicator} details</li>
 *   <li>{@code GET /actuator/metrics/discovery.*} — Micrometer counters and timers</li>
 *   <li>{@code GET /actuator/prometheus} — Prometheus scrape endpoint</li>
 * </ul>
 *
 * <p>This controller retains only the admin reset endpoint (POST to follow HTTP
 * semantics for state-mutating operations). Restrict access to internal networks
 * or authenticated admin roles at the gateway.</p>
 */
@RestController
@RequestMapping("/discovery-service/health")
public class HealthController {

    private static final Logger logger = LoggerFactory.getLogger(HealthController.class);

    private final DiscoveryMetrics metrics;

    public HealthController(DiscoveryMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Resets operational (admin-resettable) processing statistics.
     *
     * <p>Micrometer meters ({@code /actuator/prometheus}) continue to accumulate
     * — only the admin counters surfaced in {@code /actuator/health} are reset.</p>
     */
    @PostMapping("/reset-stats")
    public ResponseEntity<Map<String, Object>> resetStats() {
        try {
            metrics.resetStats();
            return ResponseEntity.ok(Map.of(
                    "message", "Statistics reset successfully",
                    "timestamp", Instant.now().toString()));
        } catch (Exception e) {
            logger.error("Failed to reset statistics", e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to reset statistics. Check server logs for details"));
        }
    }
}
