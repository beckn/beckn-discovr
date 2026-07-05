package org.beckn.catalogpublish.config;

import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.orchestration.CatalogPublishOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
@EnableScheduling // drives NetworkCache.scheduledRefresh()
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean("esIndexExecutor")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.catalog.elasticsearch.enabled", havingValue = "true")
    public Executor esIndexExecutor(AppProperties props) {
        AppProperties.Elasticsearch es = props.catalog().elasticsearch();
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(es.poolSize());
        exec.setMaxPoolSize(es.poolSize() * 2);
        exec.setQueueCapacity(es.poolQueueCapacity());
        exec.setThreadNamePrefix("es-index-");
        // AbortPolicy: reject rather than running on the caller thread.
        // CallerRunsPolicy would block the DB transaction-commit thread when the queue
        // is full, holding open the DB connection and starving the connection pool.
        exec.setRejectedExecutionHandler((r, executor) -> {
            log.error("event={} reason=executor-queue-full", LogEvent.ES_INDEX_REJECTED);
            throw new RejectedExecutionException("esIndexExecutor queue full — ES indexing task dropped");
        });
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(60);
        exec.initialize();
        return exec;
    }

    @Bean("catalogProcessingExecutor")
    public Executor catalogProcessingExecutor(AppProperties props) {
        int poolSize = props.catalog().processingPoolSize();
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(poolSize);
        exec.setMaxPoolSize(poolSize * 2);
        exec.setQueueCapacity(100);
        exec.setThreadNamePrefix("catalog-proc-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        // TaskDecorator: flags the ThreadLocal so the orchestrator knows this task
        // is running on a pool thread (vs. CallerRunsPolicy inline on the consumer
        // thread).
        exec.setTaskDecorator(runnable -> () -> {
            CatalogPublishOrchestrator.CATALOG_PROCESSING_THREAD.set(Boolean.TRUE);
            try {
                runnable.run();
            } finally {
                CatalogPublishOrchestrator.CATALOG_PROCESSING_THREAD.remove();
            }
        });
        exec.initialize();
        return exec;
    }

    /**
     * Dedicated executor for the on_pull download/ingest path
     * ({@code CatalogPullCallbackService.processPullCallbackAsynchronously}).
     *
     * <p>Isolated from {@code catalogProcessingExecutor}: a blocking HTTP download (up to 30s) plus
     * decompress + checksum must NOT run on the CPU-bound publish pool, where it would starve publish
     * throughput and — on saturation under CallerRunsPolicy there — fall back onto the HTTP request
     * thread and delay the synchronous 200 Ack. Sized for I/O (more threads than cores). on_pull is
     * low-volume, so CallerRunsPolicy is retained here (never lose a pull result); the request-thread
     * fallback is rare and, crucially, no longer contends with the publish pool.</p>
     *
     * <p>Mirrors the MDC propagation intent of the {@code catalogProcessingExecutor} decorator: the
     * submitting thread's MDC snapshot is installed on the pool thread so correlation ids survive the
     * hand-off, and is restored afterwards so ids never leak onto the next task on that thread.</p>
     */
    @Bean("onPullExecutor")
    public Executor onPullExecutor(
            @Value("${app.catalog.pull-executor-core-pool-size:4}") int corePoolSize,
            @Value("${app.catalog.pull-executor-max-pool-size:8}") int maxPoolSize,
            @Value("${app.catalog.pull-executor-queue-capacity:100}") int queueCapacity) {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(corePoolSize);
        exec.setMaxPoolSize(maxPoolSize);
        exec.setQueueCapacity(queueCapacity);
        exec.setThreadNamePrefix("onpull-");
        // CallerRunsPolicy: never lose a pull result. on_pull is low-volume so the request-thread
        // fallback is rare; the goal is isolation from the publish pool, not from the request thread.
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(60);
        // TaskDecorator: propagate the submitting thread's MDC (correlation ids) onto the pool thread,
        // then restore the pool thread's prior MDC so ids never leak onto the next scheduled task.
        exec.setTaskDecorator(runnable -> {
            java.util.Map<String, String> submitMdc = org.slf4j.MDC.getCopyOfContextMap();
            return () -> {
                java.util.Map<String, String> priorMdc = org.slf4j.MDC.getCopyOfContextMap();
                if (submitMdc != null) {
                    org.slf4j.MDC.setContextMap(submitMdc);
                } else {
                    org.slf4j.MDC.clear();
                }
                try {
                    runnable.run();
                } finally {
                    if (priorMdc != null) {
                        org.slf4j.MDC.setContextMap(priorMdc);
                    } else {
                        org.slf4j.MDC.clear();
                    }
                }
            };
        });
        exec.initialize();
        return exec;
    }
}
