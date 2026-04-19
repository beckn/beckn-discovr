package org.beckn.catalogpublish.messaging.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.dto.ProcessingResult;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.messaging.ResponsePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class KafkaResponsePublisher implements ResponsePublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaResponsePublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KafkaResponsePublisher(KafkaTemplate<String, String> kafkaTemplate,
                                  ObjectMapper objectMapper,
                                  AppProperties props) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = props.messaging().topics().responses();
    }

    @Override
    public void publishResponse(JsonNode contextNode, List<ProcessingResult> results) {
        try {
            // Use the already-parsed context node — no need to re-parse the full raw message.
            ObjectNode contextCopy = contextNode.isObject()
                    ? (ObjectNode) contextNode.deepCopy()
                    : objectMapper.createObjectNode();
            contextCopy.put(BecknFields.ACTION, BecknFields.ACTION_ON_CATALOG_PUBLISH);
            contextCopy.put(BecknFields.TIMESTAMP, Instant.now().toString());
            ObjectNode response = objectMapper.createObjectNode();
            response.set(BecknFields.CONTEXT, contextCopy);
            ObjectNode message = objectMapper.createObjectNode();
            message.set("results", objectMapper.valueToTree(results));
            response.set(BecknFields.MESSAGE, message);
            kafkaTemplate.send(topic, objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.error("event={} error={}", LogEvent.KAFKA_FAILED, e.getMessage(), e);
        }
    }
}
