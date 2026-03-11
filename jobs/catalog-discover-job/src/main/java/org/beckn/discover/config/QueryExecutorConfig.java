package org.beckn.discover.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides a dedicated {@link ExecutorService} for database parallel queries.
 *
 * <h3>Why a dedicated pool?</h3>
 * <p>{@code CompletableFuture.supplyAsync()} without an executor uses the JVM's
 * {@code ForkJoinPool.commonPool()}, which is designed for CPU-bound parallel
 * computation.  Database queries are I/O-bound; using the common pool causes
 * thread starvation under concurrent load and skews JVM parallelism statistics.
 * A dedicated, bounded pool isolates DB I/O threads from CPU-bound work.</p>
 *
 * <h3>Pool sizing</h3>
 * <p>Controlled by {@code discovery.postgresql.parallel-query-workers}
 * (default: 4).  Each parallel execution (Path A fallback) spawns exactly
 * 2 tasks (filter + spatial), so a pool of 4 supports 2 concurrent parallel
 * executions without queuing.  Increase if high concurrency is expected.</p>
 *
 * <h3>Thread naming</h3>
 * <p>Threads are named {@code discovery-query-N} for easy identification in
 * thread dumps and APM tooling.</p>
 */
@Configuration
public class QueryExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(QueryExecutorConfig.class);

    /**
     * Returns a fixed-size thread pool dedicated to discovery parallel queries.
     * The bean name {@code "discoveryQueryExecutor"} is used as the qualifier
     * in {@code DiscoveryService}.
     */
    @Bean("discoveryQueryExecutor")
    public ExecutorService discoveryQueryExecutor(DiscoveryProperties properties) {
        int workers = workers(properties);
        log.info("queryExecutor.init workers={}", workers);

        ThreadFactory factory = new NamedDaemonThreadFactory("discovery-query");
        ExecutorService executor = Executors.newFixedThreadPool(workers, factory);

        // Register shutdown hook so the pool drains gracefully on context close
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("queryExecutor.shutdown initiating");
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("queryExecutor.shutdown timed out — forcing shutdown");
                    executor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }, "discovery-query-shutdown"));

        return executor;
    }

    private static int workers(DiscoveryProperties properties) {
        if (properties != null && properties.getPostgresql() != null) {
            int configured = properties.getPostgresql().getParallelQueryWorkers();
            return configured > 0 ? configured : defaultWorkers();
        }
        return defaultWorkers();
    }

    private static int defaultWorkers() {
        // 2× the available processors, capped at 8 — suitable for I/O-bound DB work
        return Math.min(Runtime.getRuntime().availableProcessors() * 2, 8);
    }

    /** Thread factory that creates named, daemon threads. */
    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
