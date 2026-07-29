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
    private final Counter offerResolveSuccess;
    private final Counter offerResolveMissing;
    private final Counter offerResolveFailed;

    // FULL replace metrics
    private final Counter fullReplaceDeletedItems;
    private final Counter fullReplaceDeletedLocations;
    private final Counter fullReplaceDeletedEsDocs;
    private final Counter fullReplaceCount;

    // Mode-tagged persist metrics
    private final Counter persistInserted;
    private final Counter persistUpdated;
    private final Counter mergeCount;

    // Catalog metadata propagation (Phase 3.5)
    private final Counter catalogMetaPropagated;

    // Enqueue-to-Kafka (push/on_pull ingestion topic) send failures
    private final Counter enqueueFailure;

    // on_pull callback metrics use bounded tag values (mode/reason), so they are resolved
    // on demand from the registry (Micrometer caches each name+tag combination).
    private final MeterRegistry registry;

    public CatalogPublishMetrics(MeterRegistry registry) {
        this.registry = registry;
        successCounters = new EnumMap<>(CatalogOperation.class);
        failureCounters = new EnumMap<>(CatalogOperation.class);
        processingTimers = new EnumMap<>(CatalogOperation.class);

        for (CatalogOperation op : CatalogOperation.values()) {
            successCounters.put(op, Counter.builder("discovr.publish.success")
                    .description("Kafka messages processed and acknowledged by the consumer")
                    .tag("op", op.name())
                    .register(registry));
            failureCounters.put(op, Counter.builder("discovr.publish.failure")
                    .description("Kafka messages rejected at the consumer layer (parse/validation errors)")
                    .tag("op", op.name())
                    .register(registry));
            processingTimers.put(op, Timer.builder("discovr.publish.message.duration")
                    .description("End-to-end processing time per Kafka message")
                    .tag("op", op.name())
                    .register(registry));
        }

        batchPublishFailure = Counter.builder("discovr.publish.batch.failure")
                .description("Catalog batches that failed during post-commit routing/assembly")
                .register(registry);

        offerResolveSuccess = Counter.builder("discovr.publish.offer.resolve.success")
                .description("Resources updated via cross-BPP offer resolution (Phase 3)")
                .register(registry);
        offerResolveMissing = Counter.builder("discovr.publish.offer.resolve.missing")
                .description("Resource IDs referenced by offers but not found in DB")
                .register(registry);
        offerResolveFailed = Counter.builder("discovr.publish.offer.resolve.failed")
                .description("Resources that failed during cross-BPP offer merge (Phase 3)")
                .register(registry);

        // FULL replace
        fullReplaceCount = Counter.builder("discovr.publish.full.replace")
                .description("Number of FULL replace operations executed")
                .register(registry);
        fullReplaceDeletedItems = Counter.builder("discovr.publish.full.replace.deleted.resources")
                .description("Resources deleted during FULL replace")
                .register(registry);
        fullReplaceDeletedLocations = Counter.builder("discovr.publish.full.replace.deleted.locations")
                .description("Locations deleted during FULL replace")
                .register(registry);
        fullReplaceDeletedEsDocs = Counter.builder("discovr.publish.full.replace.deleted.es.docs")
                .description("ES documents deleted during FULL replace deleteByQuery")
                .register(registry);

        // MERGE mode + insert/update breakdown
        mergeCount = Counter.builder("discovr.publish.merge")
                .description("Number of MERGE operations executed")
                .register(registry);
        persistInserted = Counter.builder("discovr.publish.persist.inserted")
                .description("New resources inserted during persist")
                .register(registry);
        persistUpdated = Counter.builder("discovr.publish.persist.updated")
                .description("Existing resources updated during persist")
                .register(registry);

        catalogMetaPropagated = Counter.builder("discovr.publish.catalog.meta.propagated")
                .description("Resources rewritten to pick up changed catalog metadata (Phase 3.5)")
                .register(registry);

        enqueueFailure = Counter.builder("discovr.publish.enqueue.failure")
                .description("Failures publishing a push/on_pull payload to the ingestion Kafka topic")
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

    public void recordOfferResolveSuccess() {
        offerResolveSuccess.increment();
    }

    public void recordOfferResolveMissing(int count) {
        offerResolveMissing.increment(count);
    }

    public void recordOfferResolveFailed() {
        offerResolveFailed.increment();
    }

    public void recordFullReplace(int deletedItems, int deletedLocations) {
        fullReplaceCount.increment();
        fullReplaceDeletedItems.increment(deletedItems);
        fullReplaceDeletedLocations.increment(deletedLocations);
    }

    public void recordFullReplaceEsDeleted(long count) {
        fullReplaceDeletedEsDocs.increment(count);
    }

    public void recordMerge() {
        mergeCount.increment();
    }

    public void recordPersistInserted(int count) {
        persistInserted.increment(count);
    }

    public void recordPersistUpdated(int count) {
        persistUpdated.increment(count);
    }

    /** Resources rewritten because the publish changed catalog-level metadata (Phase 3.5). */
    public void recordCatalogMetadataPropagated(int count) {
        catalogMetaPropagated.increment(count);
    }

    /** A push/on_pull payload failed to publish to the ingestion Kafka topic. */
    public void recordEnqueueFailure() {
        enqueueFailure.increment();
    }

    /**
     * Records how long it takes to process a single Kafka message end-to-end.
     * The {@code op} tag allows filtering by operation type in dashboards.
     */
    public void recordProcessingTime(CatalogOperation op, Runnable work) {
        processingTimers.get(op).record(work);
    }

    // ── on_pull callback ingestion ──────────────────────────────────────────────
    // mode ∈ {inline, download}; reason ∈ {status_failed, empty_callback, missing_checksum,
    // missing_expiry, expired, no_catalogs, processing_error, download_http_error, ssrf_reject,
    // checksum_mismatch, decompress_error, size_exceeded, oversize, invalid_json, missing_context}
    // — all bounded, low-cardinality.

    /** A COMPLETED on_pull callback was received for processing, tagged by delivery mode. */
    public void recordOnPullReceived(String mode) {
        registry.counter("discovr.onpull.received", "mode", mode).increment();
    }

    /** An on_pull callback's catalogs were successfully enqueued to the publish pipeline. */
    public void recordOnPullProcessed(String mode) {
        registry.counter("discovr.onpull.processed", "mode", mode).increment();
    }

    /** An on_pull callback was rejected/discarded before enqueue, tagged by reason. */
    public void recordOnPullFailed(String reason) {
        registry.counter("discovr.onpull.failed", "reason", reason).increment();
    }

    /** A transient on_pull download failure (5xx / network) was retried. */
    public void recordOnPullDownloadRetry() {
        registry.counter("discovr.onpull.download.retry").increment();
    }

    // ── on_pull per-callback distributions + per-catalog counts (receiver-level) ──
    // Distributions give count+sum+max per mode; counters give per-catalog rates.
    // Bounded `mode` tag only — catalogId/networkId stay in logs, never as tag values.

    /** Number of catalogs in the on_pull payload (one record per callback), tagged by mode. */
    public void recordOnPullCatalogsReturned(String mode, int count) {
        registry.summary("discovr.onpull.catalogs.returned", "mode", mode).record(count);
    }

    /** Publisher's claimed grand total across pages (recorded only when pagination.total is present). */
    public void recordOnPullPaginationTotal(String mode, long total) {
        registry.summary("discovr.onpull.pagination.total", "mode", mode).record(total);
    }

    /** Total resources across the returned catalogs (one record per callback), tagged by mode. */
    public void recordOnPullResourcesTotal(String mode, int total) {
        registry.summary("discovr.onpull.resources.total", "mode", mode).record(total);
    }

    /** A catalog was accepted for ingestion at the receiver (well-formed, has a non-blank id). */
    public void recordOnPullCatalogAccepted(String mode) {
        registry.counter("discovr.onpull.accepted", "mode", mode).increment();
    }

    /** A catalog was rejected at the receiver (missing/blank id or not an object). */
    public void recordOnPullCatalogRejected(String mode) {
        registry.counter("discovr.onpull.rejected", "mode", mode).increment();
    }

    /** A catalog was successfully handed to the publish pipeline (enqueued). */
    public void recordOnPullCatalogProcessed(String mode) {
        registry.counter("discovr.onpull.processed.catalogs", "mode", mode).increment();
    }
}
