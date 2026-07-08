package org.beckn.catalogpublish.indexing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.dto.CatalogBatch;
import org.beckn.catalogpublish.event.CatalogPersistedEvent;
import org.beckn.catalogpublish.indexing.bulk.BulkIndexResult;
import org.beckn.catalogpublish.indexing.bulk.BulkIndexService;
import org.beckn.catalogpublish.indexing.document.CatalogDocumentAssembler;
import org.beckn.catalogpublish.indexing.failure.EsFailurePublisher;
import org.beckn.catalogpublish.model.Item;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.metrics.CatalogPublishMetrics;
import org.beckn.catalogpublish.service.embedding.EmbeddingClient;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.beckn.catalogpublish.util.MdcSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.micrometer.core.instrument.Timer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

/**
 * Post-commit ES indexing step.
 *
 * Groups items by schema type (@type from resourceAttributes) and
 * bulk-indexes
 * each group into its per-type ES index. Failed items are published to
 * the Kafka failure topic for async retry instead of being lost.
 *
 * Runs on "es-index-" thread pool — fully decoupled from the Kafka consumer
 * thread.
 */
@Component
@ConditionalOnProperty(name = "app.catalog.elasticsearch.enabled", havingValue = "true")
public class ElasticIndexStep {

    private static final Logger log = LoggerFactory.getLogger(ElasticIndexStep.class);

    private final CatalogDocumentAssembler assembler;
    private final BulkIndexService bulkIndexService;
    private final EsFailurePublisher failurePublisher;
    private final EsIndexerMetrics metrics;
    private final CatalogPublishMetrics publishMetrics;
    private final ObjectMapper mapper;
    private final int batchSize;
    private final Optional<EmbeddingClient> embeddingClient;

    public ElasticIndexStep(CatalogDocumentAssembler assembler,
            BulkIndexService bulkIndexService,
            EsFailurePublisher failurePublisher,
            EsIndexerMetrics metrics,
            CatalogPublishMetrics publishMetrics,
            ObjectMapper mapper,
            AppProperties props,
            Optional<EmbeddingClient> embeddingClient) {
        this.assembler = assembler;
        this.bulkIndexService = bulkIndexService;
        this.failurePublisher = failurePublisher;
        this.metrics = metrics;
        this.publishMetrics = publishMetrics;
        this.mapper = mapper;
        this.batchSize = props.catalog().elasticsearch().bulkBatchSize();
        this.embeddingClient = embeddingClient;
    }

    @Async("esIndexExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCatalogPersisted(CatalogPersistedEvent event) {
        MdcSupport.runWithSnapshot(event.getMdcContext(), true, () -> doIndex(event.getBatch()));
    }

    private void doIndex(CatalogBatch batch) {
        if (!batch.hasResources())
            return;

        // FULL replace: delete all existing ES documents for this catalog before indexing fresh ones.
        // This runs after the DB transaction has committed, so the DB is already clean.
        // Failure is non-fatal: stale docs will remain until the next FULL replace; new docs are still indexed.
        // TODO: PG + ES are NOT transactional. If ES delete/index fails, PG and ES diverge.
        // Consider: (1) retry ES delete via EsFailureConsumer, (2) periodic reconciliation job
        // that compares PG item_ids with ES doc_ids per catalog and removes orphans.
        if (batch.fullReplace()) {
            try {
                // Collect schema types from items to target specific indices (no wildcard)
                var schemaTypes = batch.savedResources().stream()
                        .map(Item::getType)
                        .filter(t -> t != null && !t.isBlank())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                long esDeleted = bulkIndexService.deleteByCatalog(batch.catalogId(), schemaTypes);
                publishMetrics.recordFullReplaceEsDeleted(esDeleted);
            } catch (Exception e) {
                log.error("event={} reason=es-delete-failed catalogId={} error={}",
                        LogEvent.ES_FAILED, batch.catalogId(), ErrorSanitizer.sanitize(e));
            }
        }

        // Phase 1: build docs grouped by schema type (no embedding yet)
        Map<String, List<Map<String, Object>>> bySchemaType = new LinkedHashMap<>();
        Map<String, Item> docToItem = new LinkedHashMap<>(); // doc identity map for embedding

        for (Item item : batch.savedResources()) {
            JsonNode payloadNode = batch.payloadNodes().get(item.getId());
            if (payloadNode == null) {
                log.warn("event={} reason=payload-missing itemId={}", LogEvent.ES_FAILED, item.getId());
                continue;
            }
            String schemaType = item.getType();
            if (schemaType == null || schemaType.isBlank()) {
                log.warn("event={} reason=schema-type-missing itemId={}", LogEvent.ES_FAILED, item.getId());
                continue;
            }
            List<String> networkIds = item.getNetworkIds();
            Map<String, Object> doc = assembler.assemble(item, payloadNode, schemaType, networkIds);
            bySchemaType.computeIfAbsent(schemaType, k -> new ArrayList<>()).add(doc);
            docToItem.put(item.getId(), item); // track item for embedding payload lookup
        }

        // Phase 2: H6 — batch embed in parallel across all docs in each schema-type group.
        // CompletableFuture.allOf starts all embedding HTTP calls concurrently (one per item)
        // before waiting for any to finish. Per-item failures are isolated — the item is
        // indexed without a vector rather than blocking others.
        if (embeddingClient.isPresent()) {
            var client = embeddingClient.get();
            for (Map.Entry<String, List<Map<String, Object>>> entry : bySchemaType.entrySet()) {
                List<Map<String, Object>> docs = entry.getValue();
                // Build one CompletableFuture per doc; all are submitted before joining
                @SuppressWarnings("unchecked")
                CompletableFuture<Void>[] futures = docs.stream()
                        .map(doc -> {
                            String itemId = (String) doc.get("resource_id");
                            Item item = itemId != null ? docToItem.get(itemId) : null;
                            if (item == null) return CompletableFuture.<Void>completedFuture(null);
                            JsonNode payloadNode = batch.payloadNodes().get(item.getId());
                            if (payloadNode == null) return CompletableFuture.<Void>completedFuture(null);
                            return CompletableFuture.runAsync(() -> {
                                try {
                                    // TODO (M8): extract only the resource node for better quality
                                    String itemJson = mapper.writeValueAsString(payloadNode);
                                    client.embed(itemJson).ifPresent(vec -> doc.put("resource_vector", vec));
                                } catch (Exception e) {
                                    log.warn("event={} itemId={} error={}",
                                            LogEvent.EMBEDDING_SERIALIZE_FAILED, item.getId(), e.getMessage());
                                }
                            }, ForkJoinPool.commonPool());
                        })
                        .toArray(CompletableFuture[]::new);
                // Wait for all embeddings to complete before proceeding to index
                CompletableFuture.allOf(futures).join();
            }
        }

        bySchemaType.forEach((schemaType, docs) -> indexInBatches(schemaType, docs, batch));
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void indexInBatches(String schemaType, List<Map<String, Object>> docs, CatalogBatch batch) {
        Timer.Sample timer = metrics.startBulkTimer();
        try {
            for (int i = 0; i < docs.size(); i += batchSize) {
                List<Map<String, Object>> chunk = docs.subList(i, Math.min(i + batchSize, docs.size()));
                BulkIndexResult result = bulkIndexService.index(schemaType, chunk);

                if (result.hasFailures()) {
                    publishFailures(schemaType, result, batch);
                }
                log.info("event={} schemaType={} succeeded={} failed={}",
                        LogEvent.ES_INDEXED, schemaType, result.succeeded().size(), result.failed().size());
            }
        } catch (Exception e) {
            log.error("event={} schemaType={} catalogId={} error={}",
                    LogEvent.ES_FAILED, schemaType, batch.catalogId(), ErrorSanitizer.sanitize(e));
        } finally {
            metrics.stopBulkTimer(timer);
        }
    }

    private void publishFailures(String schemaType, BulkIndexResult result, CatalogBatch batch) {
        // O(1) lookup per failure instead of O(n) linear scan
        Map<String, Item> itemById = batch.savedResources().stream()
                .collect(java.util.stream.Collectors.toMap(Item::getId, i -> i, (a, b) -> a));
        result.failed().forEach(failedDoc -> {
            Item item = itemById.get(failedDoc.resourceId());
            JsonNode payloadNode = item != null ? batch.payloadNodes().get(item.getId()) : null;
            String payloadJson = toJson(payloadNode);
            failurePublisher.publishFailures(schemaType, payloadJson, List.of(failedDoc));
        });
    }

    private String toJson(JsonNode node) {
        if (node == null)
            return "{}";
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }
}
