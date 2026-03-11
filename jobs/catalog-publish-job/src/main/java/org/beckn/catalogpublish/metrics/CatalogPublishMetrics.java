package org.beckn.catalogpublish.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.beckn.catalogpublish.dto.CatalogOperation;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Micrometer metrics for the catalog publish pipeline.
 *
 * <p>
 * All counters use fixed names without high-cardinality tag values
 * (no catalogId, no network ID) to prevent unbounded metric cardinality.
 * Per-event context is captured in structured log fields instead.
 */
@Component
public class CatalogPublishMetrics {

    private final Map<CatalogOperation, Counter> successCounters;
    private final Map<CatalogOperation, Counter> failureCounters;
    private final Counter batchPublishFailure;
    private final Map<CatalogOperation, Timer> processingTimers;

    public CatalogPublishMetrics(MeterRegistry registry) {
        successCounters = new EnumMap<>(CatalogOperation.class);
        failureCounters = new EnumMap<>(CatalogOperation.class);
        processingTimers = new EnumMap<>(CatalogOperation.class);

        for (CatalogOperation op : CatalogOperation.values()) {
            successCounters.put(op, Counter.builder("catalog.publish.success")
                    .description("Kafka messages processed and acknowledged by the consumer")
                    .tag("op", op.name())
                    .register(registry));
            failureCounters.put(op, Counter.builder("catalog.publish.failure")
                    .description("Kafka messages rejected at the consumer layer (parse/validation errors)")
                    .tag("op", op.name())
                    .register(registry));
            processingTimers.put(op, Timer.builder("catalog.publish.message.duration")
                    .description("End-to-end processing time per Kafka message")
                    .tag("op", op.name())
                    .register(registry));
        }

        batchPublishFailure = Counter.builder("catalog.batch.publish.failure")
                .description("Catalog batches that failed during post-commit routing/assembly")
                .register(registry);
    }

    public void recordMessageSuccess(CatalogOperation op) {
        successCounters.get(op).increment();
    }

    public void recordMessageRejected(CatalogOperation op) {
        failureCounters.get(op).increment();
    }

    public void recordBatchPublishFailure() {
        batchPublishFailure.increment();
    }

    /**
     * Records how long it takes to process a single Kafka message end-to-end.
     * The {@code op} tag allows filtering by operation type in dashboards.
     */
    public void recordProcessingTime(CatalogOperation op, Runnable work) {
        processingTimers.get(op).record(work);
    }
}
