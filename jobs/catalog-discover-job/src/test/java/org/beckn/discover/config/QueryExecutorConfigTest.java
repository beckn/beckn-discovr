package org.beckn.discover.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class QueryExecutorConfigTest {

    @Test
    void usesConfiguredParallelQueryWorkers() {
        DiscoveryProperties properties = new DiscoveryProperties();
        properties.getPostgresql().setParallelQueryWorkers(16);

        QueryExecutorConfig config = new QueryExecutorConfig();
        ExecutorService executor = config.discoveryQueryExecutor(properties);
        try {
            assertThat(executor).isInstanceOf(ThreadPoolExecutor.class);
            assertThat(((ThreadPoolExecutor) executor).getCorePoolSize()).isEqualTo(16);
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

        int expected = Math.min(Runtime.getRuntime().availableProcessors() * 2, 32);

        QueryExecutorConfig config = new QueryExecutorConfig();
        ExecutorService executor = config.discoveryQueryExecutor(properties);
        try {
            assertThat(executor).isInstanceOf(ThreadPoolExecutor.class);
            assertThat(((ThreadPoolExecutor) executor).getCorePoolSize()).isEqualTo(expected);
        } finally {
            executor.shutdownNow();
        }
    }
}
