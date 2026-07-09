package org.beckn.catalogpublish.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.logging.MdcField;
import org.beckn.catalogpublish.metrics.CatalogPublishMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes catalog push payloads to Kafka for async processing.
 * The HTTP response (202 Accepted) is sent before this runs, and the
 * {@link org.beckn.catalogpublish.consumer.CatalogPublishConsumer} picks up the
 * message for durable, retryable processing.
 */
@Service
public class CatalogPushService {

    private static final Logger log = LoggerFactory.getLogger(CatalogPushService.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CatalogPublishMetrics metrics;
    private final String ingestionTopic;

    public CatalogPushService(KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            CatalogPublishMetrics metrics,
            AppProperties props) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.ingestionTopic = props.messaging().topics().ingestionRequests();
    }

    /**
     * Publishes the raw catalog push payload to Kafka for async processing.
     * The message key is derived from context.subscriberId (or bppId as fallback)
     * so that one subscriber's publishes are routed to the same partition, ensuring
     * FULL replace and MERGE operations are applied in arrival order.
     * The existing {@code CatalogPublishConsumer} consumes from this topic.
     */
    public void enqueueForProcessing(String rawBody) {
        JsonNode ctx = readContext(rawBody);
        String key = extractKafkaKey(ctx);

        // The whenComplete callback runs on a Kafka producer thread that inherits NO MDC, so the
        // correlation IDs and origin are captured into locals BEFORE .send() and referenced from
        // the callback. Origin picks the success event: on_pull-origin payloads must not be
        // labelled push.received.
        final String transactionId = textOrNull(ctx, BecknFields.TRANSACTION_ID);
        final String messageId = textOrNull(ctx, BecknFields.MESSAGE_ID);
        final String subscriptionId = textOrNull(ctx, BecknFields.SUBSCRIPTION_ID);
        final boolean fromOnPull = BecknFields.ACTION_ON_CATALOG_PULL
                .equals(textOrNull(ctx, BecknFields.ACTION));
        final String successEvent = fromOnPull ? LogEvent.ON_PULL_RECEIVED : LogEvent.PUSH_RECEIVED;

        kafkaTemplate.send(ingestionTopic, key, rawBody).whenComplete((result, ex) -> {
            // The producer-network thread inherits NO MDC. Set the captured correlation IDs so
            // LogstashEncoder promotes them to structured top-level fields (consistent with every
            // other flow line), then clear them so they never leak into another record's callback.
            putIfNotNull(MdcField.TRANSACTION_ID, transactionId);
            putIfNotNull(MdcField.MESSAGE_ID, messageId);
            putIfNotNull(MdcField.SUBSCRIPTION_ID, subscriptionId);
            try {
                if (ex != null) {
                    metrics.recordEnqueueFailure();
                    log.error("event={} topic={} error={}",
                            LogEvent.KAFKA_FAILED, ingestionTopic, ex.getMessage(), ex);
                } else {
                    log.info("event={} topic={} offset={}",
                            successEvent, ingestionTopic, result.getRecordMetadata().offset());
                }
            } finally {
                MDC.remove(MdcField.TRANSACTION_ID);
                MDC.remove(MdcField.MESSAGE_ID);
                MDC.remove(MdcField.SUBSCRIPTION_ID);
            }
        });
    }

    /** Parses the context node once, or a missing node on any parse failure (delivery never blocked). */
    private JsonNode readContext(String rawBody) {
        try {
            return objectMapper.readTree(rawBody).path(BecknFields.CONTEXT);
        } catch (Exception e) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
    }

    private static String textOrNull(JsonNode ctx, String field) {
        String v = ctx.path(field).asText(null);
        return (v != null && !v.isBlank()) ? v : null;
    }

    /** Sets an MDC key only when the value is non-null (avoids writing empty correlation fields). */
    private static void putIfNotNull(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }

    /**
     * Extracts a stable partition key from the parsed context for ordering guarantees.
     * One subscriber's publishes must be sequential (FULL replace + MERGE ordering).
     * Priority: context.subscriberId → context.bppId → null (round-robin).
     * A missing/blank context yields null so delivery is never blocked.
     */
    private String extractKafkaKey(JsonNode ctx) {
        String subscriberId = ctx.path(BecknFields.SUBSCRIBER_ID).asText(null);
        if (subscriberId != null && !subscriberId.isBlank()) return subscriberId;
        String bppId = ctx.path(BecknFields.BPP_ID).asText(null);
        if (bppId != null && !bppId.isBlank()) return bppId;
        return null;
    }
}
