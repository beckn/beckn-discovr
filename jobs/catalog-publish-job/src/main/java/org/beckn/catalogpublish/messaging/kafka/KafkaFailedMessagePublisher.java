package org.beckn.catalogpublish.messaging.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.messaging.FailedMessagePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class KafkaFailedMessagePublisher implements FailedMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaFailedMessagePublisher.class);
    private static final int MAX_SNIPPET_LEN = 200;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KafkaFailedMessagePublisher(KafkaTemplate<String, String> kafkaTemplate,
                                       ObjectMapper objectMapper,
                                       AppProperties props) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = props.messaging().topics().failed();
    }

    @Override
    public void publishFailed(String originalMessage, String reason) {
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("reason", reason != null ? reason : "unknown");
            envelope.put("timestamp", Instant.now().toString());
            if (originalMessage != null) {
                envelope.put("snippet", originalMessage.substring(0, Math.min(originalMessage.length(), MAX_SNIPPET_LEN)));
            }
            kafkaTemplate.send(topic, objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.error("event={} reason={}", LogEvent.KAFKA_FAILED, reason, e);
        }
    }
}
