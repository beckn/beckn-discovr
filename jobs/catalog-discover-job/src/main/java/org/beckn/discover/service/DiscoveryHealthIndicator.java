package org.beckn.discover.service;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Spring Boot Actuator {@link HealthIndicator} for the Discovery Service.
 *
 * <p>Exposed at {@code GET /actuator/health} (and the composite
 * {@code /actuator/health/discoveryHealth} component). Includes request
 * statistics as health details for operational dashboards.</p>
 *
 * <p>Replaces the former {@code GET /discovery-service/health/detailed} and
 * {@code GET /discovery-service/health/stats} endpoints from the hand-rolled
 * {@link org.beckn.discover.controller.HealthController}.</p>
 */
@Component
public class DiscoveryHealthIndicator implements HealthIndicator {

    private final DiscoveryMetrics metrics;

    public DiscoveryHealthIndicator(DiscoveryMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        DiscoveryMetrics.ProcessingStats stats = metrics.getProcessingStats();

        if (metrics.isHealthy()) {
            return Health.up()
                    .withDetail("totalRequests",          stats.totalRequests())
                    .withDetail("successfulRequests",     stats.successfulRequests())
                    .withDetail("failedRequests",         stats.failedRequests())
                    .withDetail("successRate",            String.format("%.2f%%", stats.successRate()))
                    .withDetail("failureRate",            String.format("%.2f%%", stats.failureRate()))
                    .withDetail("averageProcessingTimeMs", String.format("%.2f ms", stats.averageProcessingTimeMs()))
                    .build();
        }

        return Health.down()
                .withDetail("reason", "Service reports unhealthy state")
                .build();
    }
}
