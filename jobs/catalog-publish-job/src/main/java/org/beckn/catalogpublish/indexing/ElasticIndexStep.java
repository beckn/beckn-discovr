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

/**
 * Post-commit ES indexing step.
 *
 * Groups items by schema type (@type from beckn:itemAttributes) and
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
    private final ObjectMapper mapper;
    private final int batchSize;
    private final Optional<EmbeddingClient> embeddingClient;

    public ElasticIndexStep(CatalogDocumentAssembler assembler,
            BulkIndexService bulkIndexService,
            EsFailurePublisher failurePublisher,
            EsIndexerMetrics metrics,
            ObjectMapper mapper,
            AppProperties props,
            Optional<EmbeddingClient> embeddingClient) {
        this.assembler = assembler;
        this.bulkIndexService = bulkIndexService;
        this.failurePublisher = failurePublisher;
        this.metrics = metrics;
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
        if (!batch.hasItems())
            return;

        Map<String, List<Map<String, Object>>> bySchemaType = new LinkedHashMap<>();
        for (Item item : batch.savedItems()) {
            JsonNode payloadNode = batch.payloadNodes().get(item.getId());
            if (payloadNode == null) {
                log.warn("es.index.payload.missing itemId={}", item.getId());
                continue;
            }
            String schemaType = item.getType();
            if (schemaType == null || schemaType.isBlank()) {
                log.warn("es.index.schema.type.missing itemId={}", item.getId());
                continue;
            }
            String[] networkIds = item.getNetworkIds();
            String networkId = networkIds.length > 0 ? networkIds[0] : null;
            Map<String, Object> doc = assembler.assemble(item, payloadNode, schemaType, networkId);
            embeddingClient.ifPresent(client -> {
                Object blob = doc.get("full_text_blob");
                if (blob instanceof String text && !text.isBlank())
                    client.embed(text).ifPresent(vec -> doc.put("item_vector", vec));
            });
            bySchemaType.computeIfAbsent(schemaType, k -> new ArrayList<>()).add(doc);
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
                log.debug("es.batch.indexed network={} succeeded={} failed={}",
                        schemaType, result.succeeded().size(), result.failed().size());
            }
        } catch (Exception e) {
            log.error("es.index.step.error schemaType={} catalogId={} error={}",
                    schemaType, batch.catalogId(), ErrorSanitizer.sanitize(e));
        } finally {
            metrics.stopBulkTimer(timer);
        }
    }

    private void publishFailures(String schemaType, BulkIndexResult result, CatalogBatch batch) {
        result.failed().forEach(failedDoc -> {
            Item item = batch.savedItems().stream()
                    .filter(i -> failedDoc.itemId().equals(i.getId()))
                    .findFirst().orElse(null);
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
