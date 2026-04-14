package org.beckn.discover.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.common.BecknFields;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.logging.BecknMdcContext;
import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.model.DiscoverResponse;
import org.beckn.discover.service.DiscoveryService;
import org.beckn.discover.service.validation.DiscoveryValidationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.value;

/**
 * Kafka Consumer for Discovery Events.
 *
 * <p>Applies schema validation before dispatching to {@link DiscoveryService},
 * equivalent to the HTTP path.  Auth is not re-applied here because the Kafka
 * topic must be access-controlled at the broker level (ACLs).</p>
 *
 * <h3>Acknowledgement strategy</h3>
 * <ul>
 *   <li>Parse failure — <b>not acknowledged</b>; Kafka retries per consumer
 *       config and routes to DLT after {@code max.poll.records} retries.</li>
 *   <li>Validation failure — <b>acknowledged</b>; the message is malformed
 *       and retrying would loop forever.  Error is logged for human review.</li>
 *   <li>Processing failure — <b>acknowledged</b>; transient errors (DB down)
 *       are retried at the service level via {@code @Retryable}.  Logging
 *       ensures observability.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "discovery.kafka.request-topic")
public class DiscoveryEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final DiscoveryService discoveryService;
    private final DiscoveryValidationService validationService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DiscoveryProperties discoveryProperties;

    public DiscoveryEventConsumer(
            ObjectMapper objectMapper,
            DiscoveryService discoveryService,
            DiscoveryValidationService validationService,
            KafkaTemplate<String, String> kafkaTemplate,
            DiscoveryProperties discoveryProperties) {
        this.objectMapper = objectMapper;
        this.discoveryService = discoveryService;
        this.validationService = validationService;
        this.kafkaTemplate = kafkaTemplate;
        this.discoveryProperties = discoveryProperties;
    }

    @KafkaListener(
        topics = "${discovery.kafka.request-topic}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleDiscoveryEvent(
            @Payload String message,
            Acknowledgment acknowledgment,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long timestamp,
            @Header(value = "tags", required = false) byte[] tagsHeader) {

        BecknMdcContext.setTags(tagsHeader);

        logger.info(LogEvent.CONSUMER_RECEIVED,
                value("partition", partition),
                value("offset", offset),
                value("timestamp", timestamp));

        // 1. Parse — do NOT ack on failure; let Kafka retry / route to DLT
        JsonNode requestNode;
        try {
            requestNode = objectMapper.readTree(message);
        } catch (Exception e) {
            logger.error(LogEvent.CONSUMER_PARSE_FAILED,
                    value("partition", partition),
                    value("offset", offset),
                    value("rawMessage", truncate(message, 2000)),
                    e);
            throw new RuntimeException("Failed to parse discovery event", e); // triggers DefaultErrorHandler retry / DLT
        }

        // Populate MDC as early as possible
        BecknMdcContext.populate(requestNode.path(BecknFields.CONTEXT));

        try {
            // 2. Schema validation — ack on failure to avoid infinite retry loops
            DiscoveryValidationService.ValidationResult validation =
                    validationService.validateDiscoverRequest(requestNode);
            if (!validation.isValid()) {
                logger.error(LogEvent.CONSUMER_VALIDATE_FAILED,
                        value("partition", partition),
                        value("offset", offset),
                        value("errors", validation.getErrors()),
                        value("rawMessage", truncate(message, 2000)));
                acknowledgment.acknowledge();
                return;
            }

            // 3. Process
            DiscoverRequest discoverRequest = objectMapper.treeToValue(requestNode, DiscoverRequest.class);

            // context-level MDC fields (messageId, bapId, transactionId) already set by BecknMdcContext.populate above

            DiscoverResponse response = discoveryService.processDiscoveryRequest(discoverRequest);

            // Publish the response BEFORE acknowledging so that a publish failure keeps
            // the message un-acked and allows the container error handler to retry/DLT it.
            // If we ack first and publish fails, the BAP never receives its callback.
            publishResponse(response, discoverRequest);

            acknowledgment.acknowledge();
            logger.info(LogEvent.QUERY_COMPLETED,
                    value("partition", partition),
                    value("offset", offset));

        } catch (Exception e) {
            logger.error(LogEvent.QUERY_FAILED,
                    value("partition", partition),
                    value("offset", offset),
                    e);
            throw new RuntimeException("Discovery processing failed", e); // triggers DefaultErrorHandler retry / DLT
        } finally {
            BecknMdcContext.clear();
        }
    }

    /**
     * Publishes the discovery response to the Kafka response topic.
     *
     * <p>This method blocks until the send completes so that the caller can decide
     * whether to acknowledge the inbound message.  If the send fails, a
     * {@link RuntimeException} is thrown — the caller's catch block will
     * rethrow it, keeping the inbound message un-acked so the container
     * error handler can retry or route it to the DLT.</p>
     *
     * <p>If the response topic is not configured, the failure is logged as a
     * warning and the method returns normally (misconfiguration is an ops issue,
     * not a message-level retry candidate).</p>
     */
    private void publishResponse(DiscoverResponse response, DiscoverRequest request) {
        String responseTopic = discoveryProperties.getKafka().getResponseTopic();
        if (responseTopic == null || responseTopic.isBlank()) {
            logger.warn(LogEvent.RESPONSE_PUBLISH_FAILED,
                    value("reason", "response-topic-not-configured"));
            return;
        }
        String transactionId = request.getContext() != null ? request.getContext().getTransactionId() : null;
        try {
            String responseJson = objectMapper.writeValueAsString(response);
            // Block until Kafka broker confirms receipt so that any send failure propagates
            // before the inbound offset is acknowledged.
            kafkaTemplate.send(responseTopic, transactionId, responseJson).get();
            logger.info(LogEvent.RESPONSE_PUBLISHED,
                    value("topic", responseTopic));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error(LogEvent.RESPONSE_PUBLISH_FAILED,
                    value("topic", responseTopic),
                    e);
            throw new RuntimeException("Interrupted while publishing response", e);
        } catch (Exception e) {
            logger.error(LogEvent.RESPONSE_PUBLISH_FAILED,
                    value("topic", responseTopic),
                    e);
            throw new RuntimeException("Failed to publish on_discover response", e);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...[truncated]";
    }
}
