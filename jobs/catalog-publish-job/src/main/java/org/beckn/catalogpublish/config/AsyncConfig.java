package org.beckn.catalogpublish.config;

import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.orchestration.CatalogPublishOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            CatalogPublishOrchestrator.CATALOG_PROC_THREAD.set(Boolean.TRUE);
            try {
                runnable.run();
            } finally {
                CatalogPublishOrchestrator.CATALOG_PROC_THREAD.remove();
            }
        });
        exec.initialize();
        return exec;
    }
}
