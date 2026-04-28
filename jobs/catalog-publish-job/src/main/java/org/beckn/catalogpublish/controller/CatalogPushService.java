package org.beckn.catalogpublish.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final String ingestionTopic;

    public CatalogPushService(KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            AppProperties props) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
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
        String key = extractKafkaKey(rawBody);
        kafkaTemplate.send(ingestionTopic, key, rawBody).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("event={} topic={} error={}", LogEvent.CONSUMER_ERROR, ingestionTopic, ex.getMessage(), ex);
            } else {
                log.info("event={} topic={} offset={}",
                        LogEvent.PUSH_RECEIVED, ingestionTopic, result.getRecordMetadata().offset());
            }
        });
    }

    /**
     * Extracts a stable partition key from the raw body for ordering guarantees.
     * One subscriber's publishes must be sequential (FULL replace + MERGE ordering).
     * Priority: context.subscriberId → context.bppId → null (round-robin).
     * Falls back to null on any parse failure so delivery is never blocked.
     */
    private String extractKafkaKey(String rawBody) {
        try {
            JsonNode ctx = objectMapper.readTree(rawBody).path(BecknFields.CONTEXT);
            String subscriberId = ctx.path(BecknFields.SUBSCRIBER_ID).asText(null);
            if (subscriberId != null && !subscriberId.isBlank()) return subscriberId;
            String bppId = ctx.path(BecknFields.BPP_ID).asText(null);
            if (bppId != null && !bppId.isBlank()) return bppId;
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
