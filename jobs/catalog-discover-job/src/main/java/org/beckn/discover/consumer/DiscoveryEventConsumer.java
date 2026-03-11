package org.beckn.discover.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.service.DiscoveryService;
import org.beckn.discover.service.validation.DiscoveryValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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

    public DiscoveryEventConsumer(
            ObjectMapper objectMapper,
            DiscoveryService discoveryService,
            DiscoveryValidationService validationService) {
        this.objectMapper = objectMapper;
        this.discoveryService = discoveryService;
        this.validationService = validationService;
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
            @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long timestamp) {

        logger.info("Received discovery event partition={} offset={} timestamp={}", partition, offset, timestamp);

        // 1. Parse — do NOT ack on failure; let Kafka retry / route to DLT
        JsonNode requestNode;
        try {
            requestNode = objectMapper.readTree(message);
        } catch (Exception e) {
            logger.error("Failed to parse Kafka message partition={} offset={} — not acknowledging",
                    partition, offset, e);
            return; // no ack → Kafka retries
        }

        // 2. Schema validation — ack on failure to avoid infinite retry loops
        DiscoveryValidationService.ValidationResult validation =
                validationService.validateDiscoverRequest(requestNode);
        if (!validation.isValid()) {
            logger.error("Kafka message failed schema validation partition={} offset={} errors={} — acknowledging to prevent infinite retry",
                    partition, offset, validation.getErrors());
            acknowledgment.acknowledge();
            return;
        }

        // 3. Process
        try {
            DiscoverRequest discoverRequest = objectMapper.treeToValue(requestNode, DiscoverRequest.class);

            if (discoverRequest.getContext() != null) {
                logger.info("Processing discovery request messageId={} bapId={} transactionId={}",
                        discoverRequest.getContext().getMessageId(),
                        discoverRequest.getContext().getBapId(),
                        discoverRequest.getContext().getTransactionId());
            }

            discoveryService.processDiscoveryRequest(discoverRequest);
            acknowledgment.acknowledge();
            logger.info("Successfully processed discovery event partition={} offset={}", partition, offset);

        } catch (Exception e) {
            logger.error("Error processing discovery event partition={} offset={} — acknowledging to prevent infinite retry",
                    partition, offset, e);
            acknowledgment.acknowledge();
        }
    }
}
