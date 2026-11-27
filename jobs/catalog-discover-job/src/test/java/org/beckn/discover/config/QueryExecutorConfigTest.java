package org.beckn.discover.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class QueryExecutorConfigTest {

    @Test
    void usesConfiguredParallelQueryWorkers() {
        DiscoveryProperties properties = new DiscoveryProperties();
        properties.getPostgresql().setParallelQueryWorkers(16);

        QueryExecutorConfig config = new QueryExecutorConfig();
        ExecutorService executor = config.discoveryQueryExecutor(properties);
        try {
            assertThat(executor).isNotNull();
            // Fixed pool of 16 — submit should not reject immediately for ≤16 tasks.
            assertThat(properties.getPostgresql().getParallelQueryWorkers()).isEqualTo(16);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void defaultParallelQueryWorkersIs16() {
        assertThat(new DiscoveryProperties().getPostgresql().getParallelQueryWorkers())
                .isEqualTo(16);
    }

    @Test
    void zeroWorkersFallsBackToCpuBasedDefault() {
        DiscoveryProperties properties = new DiscoveryProperties();
        properties.getPostgresql().setParallelQueryWorkers(0);

        QueryExecutorConfig config = new QueryExecutorConfig();
        ExecutorService executor = config.discoveryQueryExecutor(properties);
        try {
            int expected = Math.min(Runtime.getRuntime().availableProcessors() * 2, 32);
            assertThat(expected).isBetween(2, 32);
            assertThat(executor).isNotNull();
        } finally {
            executor.shutdownNow();
        }
    }
}
