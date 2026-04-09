package org.beckn.catalogpublish.controller;

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
    private final String ingestionTopic;

    public CatalogPushService(KafkaTemplate<String, String> kafkaTemplate,
            AppProperties props) {
        this.kafkaTemplate = kafkaTemplate;
        this.ingestionTopic = props.messaging().topics().ingestionRequests();
    }

    /**
     * Publishes the raw catalog push payload to Kafka for async processing.
     * The existing {@code CatalogPublishConsumer} consumes from this topic.
     */
    public void processAsync(String rawBody) {
        kafkaTemplate.send(ingestionTopic, rawBody).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("event={} topic={} error={}", LogEvent.CONSUMER_ERROR, ingestionTopic, ex.getMessage(), ex);
            } else {
                log.info("event={} topic={} offset={}",
                        LogEvent.PUSH_RECEIVED, ingestionTopic, result.getRecordMetadata().offset());
            }
        });
    }
}
