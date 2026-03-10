package org.beckn.catalogpublish.indexing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "app.catalog.elasticsearch.enabled", havingValue = "true")
public class EsIndexerMetrics {

    private final Counter indexed;
    private final Counter itemFailure;
    private final Counter batchFailure;
    private final Counter retried;
    private final Counter recovered;
    private final Counter permanentFailure;
    private final Counter indexCreated;
    private final Timer   bulkDuration;
    private final AtomicLong pendingFailures = new AtomicLong(0);

    public EsIndexerMetrics(MeterRegistry registry) {
        indexed          = counter(registry, "es.indexer.item.indexed",       "Items successfully indexed to ES");
        itemFailure      = counter(registry, "es.indexer.item.failure",        "Per-item bulk failures");
        batchFailure     = counter(registry, "es.indexer.batch.failure",       "Full batch-level failures");
        retried          = counter(registry, "es.indexer.retry",               "Bulk retry attempts");
        recovered        = counter(registry, "es.indexer.recovered",           "Items recovered by failure consumer");
        permanentFailure = counter(registry, "es.indexer.permanent.failure",   "Items exceeding max retry attempts");
        indexCreated     = counter(registry, "es.indexer.index.created",       "ES indices created for root networks");
        bulkDuration     = Timer.builder("es.indexer.bulk.duration")
                                .description("End-to-end bulk request latency")
                                .register(registry);
        Gauge.builder("es.indexer.failures.pending", pendingFailures, AtomicLong::get)
             .description("Items currently queued in the ES failure topic")
             .register(registry);
    }

    public void incrementIndexed()          { indexed.increment(); }
    public void incrementItemFailure()      { itemFailure.increment(); }
    public void incrementBatchFailure()     { batchFailure.increment(); }
    public void incrementRetried()          { retried.increment(); }
    public void incrementRecovered()        { recovered.increment(); }
    public void incrementPermanentFailure() { permanentFailure.increment(); }
    public void incrementIndexCreated()     { indexCreated.increment(); }
    public Timer.Sample startBulkTimer()    { return Timer.start(); }
    public void stopBulkTimer(Timer.Sample s) { s.stop(bulkDuration); }
    public void setPendingFailures(long n)  { pendingFailures.set(n); }

    private static Counter counter(MeterRegistry r, String name, String desc) {
        return Counter.builder(name).description(desc).register(r);
    }
}
