package org.beckn.catalogpublish.indexing.failure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.indexing.EsIndexerMetrics;
import org.beckn.catalogpublish.indexing.EsIndexManager;
import org.beckn.catalogpublish.indexing.bulk.BulkIndexResult;
import org.beckn.catalogpublish.indexing.bulk.BulkIndexService;
import org.beckn.catalogpublish.indexing.document.CatalogDocumentAssembler;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Retries ES indexing for items that failed in the main pipeline.
 * Uses AckMode.RECORD (configured in EsIndexingConfig) so an uncommitted
 * offset means a pending failure — no DB table needed.
 *
 * Flow:
 *   attempt < maxFailureAttempts → retry → success: commit offset
 *                                         → fail:    republish (attempt+1)
 *   attempt >= maxFailureAttempts → route to final DLQ, commit offset
 */
@Component
@ConditionalOnProperty(name = "app.catalog.elasticsearch.enabled", havingValue = "true")
public class EsFailureConsumer {

    private static final Logger log = LoggerFactory.getLogger(EsFailureConsumer.class);

    private final ObjectMapper             mapper;
    private final CatalogDocumentAssembler assembler;
    private final BulkIndexService         bulkIndexService;
    private final EsFailurePublisher       failurePublisher;
    private final EsIndexerMetrics         metrics;
    private final KafkaTemplate<String, String> kafka;
    private final String                   finalDlqTopic;
    private final int                      maxAttempts;

    public EsFailureConsumer(ObjectMapper mapper,
                             CatalogDocumentAssembler assembler,
                             BulkIndexService bulkIndexService,
                             EsFailurePublisher failurePublisher,
                             EsIndexerMetrics metrics,
                             KafkaTemplate<String, String> kafka,
                             AppProperties props) {
        this.mapper           = mapper;
        this.assembler        = assembler;
        this.bulkIndexService = bulkIndexService;
        this.failurePublisher = failurePublisher;
        this.metrics          = metrics;
        this.kafka            = kafka;
        this.finalDlqTopic    = props.catalog().elasticsearch().finalDlqTopic();
        this.maxAttempts      = props.catalog().elasticsearch().maxFailureAttempts();
    }

    @KafkaListener(
            topics        = "${app.catalog.elasticsearch.failure-topic}",
            groupId       = "catalog-es-recovery",
            containerFactory = "esRecoveryListenerContainerFactory")
    public void consume(String json) {
        EsFailureMessage msg;
        try {
            msg = mapper.readValue(json, EsFailureMessage.class);
        } catch (Exception e) {
            log.error("event={} reason=parse-error error={}", LogEvent.CONSUMER_ERROR, ErrorSanitizer.sanitize(e));
            // Route unparseable message to DLQ so operators can investigate — don't silently drop
            try {
                kafka.send(finalDlqTopic, "unknown", json);
            } catch (Exception dlqErr) {
                log.error("event={} reason=dlq-publish-failed error={}", LogEvent.KAFKA_FAILED, ErrorSanitizer.sanitize(dlqErr));
            }
            return;
        }

        if (msg.attempt() >= maxAttempts) {
            routeToDlq(msg);
            return;
        }

        try {
            JsonNode payloadNode = mapper.readTree(msg.payload());
            Map<String, Object> doc = assembler.assemble(payloadNode, msg.indexKey());
            BulkIndexResult result = bulkIndexService.index(msg.indexKey(), List.of(doc));

            if (result.hasFailures()) {
                log.warn("event={} reason=retry-failed itemId={} attempt={}",
                        LogEvent.ES_FAILED, msg.itemId(), msg.attempt());
                failurePublisher.republish(msg);
            } else {
                metrics.incrementRecovered();
                log.info("event={} itemId={}", LogEvent.ES_INDEXED, msg.itemId());
            }
        } catch (Exception e) {
            log.error("event={} itemId={} error={}",
                    LogEvent.ES_FAILED, msg.itemId(), ErrorSanitizer.sanitize(e));
            failurePublisher.republish(msg);
        }
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void routeToDlq(EsFailureMessage msg) {
        metrics.incrementPermanentFailure();
        log.error("event={} reason=permanent-failure itemId={} bppId={} attempts={}", LogEvent.ES_FAILED, msg.itemId(), msg.bppId(), msg.attempt());
        try {
            kafka.send(finalDlqTopic, msg.bppId(), mapper.writeValueAsString(msg));
        } catch (Exception e) {
            log.error("event={} reason=dlq-publish-failed itemId={} error={}",
                    LogEvent.KAFKA_FAILED, msg.itemId(), ErrorSanitizer.sanitize(e));
        }
    }
}
