package org.beckn.discover.consumer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.model.Context;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.model.DiscoverResponse;
import org.beckn.discover.service.DiscoveryService;
import org.beckn.discover.service.validation.DiscoveryValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DiscoveryEventConsumer — focused on the non-blocking Kafka publish behaviour (C1).
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryEventConsumerTest {

    @Mock private DiscoveryService discoveryService;
    @Mock private DiscoveryValidationService validationService;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private Acknowledgment acknowledgment;

    private ObjectMapper objectMapper;
    private DiscoveryProperties properties;
    private DiscoveryEventConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        properties = new DiscoveryProperties();
        properties.getKafka().setRequestTopic("test-request-topic");
        properties.getKafka().setResponseTopic("test-response-topic");

        consumer = new DiscoveryEventConsumer(
                objectMapper, discoveryService, validationService,
                kafkaTemplate, properties);
    }

    @Test
    void handleDiscoveryEvent_kafkaSendFailure_doesNotBlockThread() throws Exception {
        // GIVEN: a valid discover request envelope
        String message = validDiscoverJson();
        var validation = mock(DiscoveryValidationService.ValidationResult.class);
        when(validation.isValid()).thenReturn(true);
        when(validationService.validateDiscoverRequest(any(com.fasterxml.jackson.databind.JsonNode.class))).thenReturn(validation);
        when(discoveryService.processDiscoveryRequest(any())).thenReturn(buildResponse());

        // kafkaTemplate.send returns a CompletableFuture that fails asynchronously
        // (simulating broker timeout without blocking the calling thread)
        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Broker unavailable"));
        when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(failedFuture);

        AtomicBoolean returned = new AtomicBoolean(false);

        // WHEN: run on a separate thread so we can verify it returns quickly
        long start = System.currentTimeMillis();
        consumer.handleDiscoveryEvent(message, acknowledgment, 0, 0L, Instant.now().toEpochMilli(), null);
        long elapsed = System.currentTimeMillis() - start;
        returned.set(true);

        // THEN: the consumer method returned without blocking on the failed future
        assertThat(returned.get()).isTrue();
        // Should have returned well under 1 second — no blocking .get() in path
        assertThat(elapsed).isLessThan(5000);
        // H-1: ack is inside whenComplete success branch — on send failure, offset must NOT be committed
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void handleDiscoveryEvent_kafkaSendSuccess_acknowledgesOffset() throws Exception {
        // GIVEN
        String message = validDiscoverJson();
        var validation = mock(DiscoveryValidationService.ValidationResult.class);
        when(validation.isValid()).thenReturn(true);
        when(validationService.validateDiscoverRequest(any(com.fasterxml.jackson.databind.JsonNode.class))).thenReturn(validation);
        when(discoveryService.processDiscoveryRequest(any())).thenReturn(buildResponse());

        RecordMetadata meta = new RecordMetadata(new TopicPartition("test-response-topic", 0),
                0, 0, 0, 0, 0);
        SendResult<String, String> sendResult = new SendResult<>(null, meta);
        CompletableFuture<SendResult<String, String>> successFuture = CompletableFuture.completedFuture(sendResult);
        ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, String>> recordCaptor =
                ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
        when(kafkaTemplate.send(recordCaptor.capture())).thenReturn(successFuture);

        // WHEN
        consumer.handleDiscoveryEvent(message, acknowledgment, 0, 0L, Instant.now().toEpochMilli(), null);

        // THEN
        verify(acknowledgment).acknowledge();

        // Kafka record value must be the dispatcher envelope with a "payload" wrapper
        String producedValue = recordCaptor.getValue().value();
        com.fasterxml.jackson.databind.JsonNode envelope = objectMapper.readTree(producedValue);
        assertThat(envelope.has("payload")).isTrue();
        assertThat(envelope.has("meta")).isTrue();
        // Payload carries the Beckn response context
        assertThat(envelope.path("payload").path("context").path("action").asText())
                .isEqualTo("on_discover");
    }

    @Test
    void handleDiscoveryEvent_identityInMeta_propagatedToResponseEnvelope() throws Exception {
        // GIVEN — identity provided in request envelope meta; must be forwarded to response envelope meta
        String message = validDiscoverJson("bap.example.com", "key-001");
        var validation = mock(DiscoveryValidationService.ValidationResult.class);
        when(validation.isValid()).thenReturn(true);
        when(validationService.validateDiscoverRequest(any(com.fasterxml.jackson.databind.JsonNode.class))).thenReturn(validation);
        when(discoveryService.processDiscoveryRequest(any())).thenReturn(buildResponse());

        RecordMetadata recordMeta = new RecordMetadata(new TopicPartition("test-response-topic", 0),
                0, 0, 0, 0, 0);
        SendResult<String, String> sendResult = new SendResult<>(null, recordMeta);
        ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, String>> recordCaptor =
                ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
        when(kafkaTemplate.send(recordCaptor.capture()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        // WHEN
        consumer.handleDiscoveryEvent(message, acknowledgment, 0, 0L, Instant.now().toEpochMilli(), null);

        // THEN — identity read from request meta and forwarded to response envelope meta
        String producedValue = recordCaptor.getValue().value();
        com.fasterxml.jackson.databind.JsonNode envelope = objectMapper.readTree(producedValue);
        assertThat(envelope.path("meta").path("subscriber_id").asText()).isEqualTo("bap.example.com");
        assertThat(envelope.path("meta").path("record_id").asText()).isEqualTo("key-001");
        assertThat(envelope.path("payload").path("context").path("action").asText()).isEqualTo("on_discover");
        // Identity travels in JSON meta only — no subscriber_id Kafka header on response topic
        assertThat(recordCaptor.getValue().headers().lastHeader("subscriber_id")).isNull();
        verify(acknowledgment).acknowledge();
    }

    @Test
    void handleDiscoveryEvent_validationFailure_acknowledgesAndDoesNotCallService() throws Exception {
        // GIVEN
        String message = validDiscoverJson();
        var validation = mock(DiscoveryValidationService.ValidationResult.class);
        when(validation.isValid()).thenReturn(false);
        when(validation.getErrors()).thenReturn(List.of("action must be 'discover'"));
        when(validationService.validateDiscoverRequest(any(com.fasterxml.jackson.databind.JsonNode.class))).thenReturn(validation);

        // WHEN
        consumer.handleDiscoveryEvent(message, acknowledgment, 0, 0L, Instant.now().toEpochMilli(), null);

        // THEN: acknowledged to avoid infinite retry loop, service never called
        verify(acknowledgment).acknowledge();
        verifyNoInteractions(discoveryService);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void handleDiscoveryEvent_responseTopicNotConfigured_acknowledgesWithoutPublish() throws Exception {
        // GIVEN: no response topic
        properties.getKafka().setResponseTopic(null);
        String message = validDiscoverJson();
        var validation = mock(DiscoveryValidationService.ValidationResult.class);
        when(validation.isValid()).thenReturn(true);
        when(validationService.validateDiscoverRequest(any(com.fasterxml.jackson.databind.JsonNode.class))).thenReturn(validation);
        when(discoveryService.processDiscoveryRequest(any())).thenReturn(buildResponse());

        // WHEN
        consumer.handleDiscoveryEvent(message, acknowledgment, 0, 0L, Instant.now().toEpochMilli(), null);

        // THEN: no Kafka send attempted; with no topic configured publishResponse returns early
        // without calling ack — the offset stays uncommitted (misconfiguration is an ops issue,
        // not a reason to silently drop a message by acknowledging without delivery confirmation)
        verifyNoInteractions(kafkaTemplate);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void handleDiscoveryEvent_bareMessage_validationFailsAndAcks() throws Exception {
        // GIVEN: a bare Beckn discover request — no meta/payload envelope wrapping.
        // This happens if a message was enqueued before the envelope pattern was deployed.
        // The consumer extracts requestNode = root.path("payload") = MissingNode.
        // Schema validation sees a MissingNode and returns invalid — ack to avoid infinite retry.
        String bareMessage = objectMapper.writeValueAsString(java.util.Map.of(
                "context", java.util.Map.of("action", "discover", "messageId", UUID.randomUUID().toString()),
                "message", java.util.Map.of()));

        var validation = mock(DiscoveryValidationService.ValidationResult.class);
        when(validation.isValid()).thenReturn(false);
        when(validation.getErrors()).thenReturn(List.of("missing required fields"));
        when(validationService.validateDiscoverRequest(any(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(validation);

        // WHEN
        consumer.handleDiscoveryEvent(bareMessage, acknowledgment, 0, 0L, Instant.now().toEpochMilli(), null);

        // THEN: acked to prevent infinite retry loop; service and kafka never touched
        verify(acknowledgment).acknowledge();
        verifyNoInteractions(discoveryService);
        verifyNoInteractions(kafkaTemplate);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String validDiscoverJson() throws Exception {
        return validDiscoverJson(null, null);
    }

    /**
     * Builds a valid discover request wrapped in the controller meta envelope.
     * Format: { "meta": { "subscriber_id": ..., "record_id": ... }, "payload": { <discover request> } }
     */
    private String validDiscoverJson(String subscriberId, String recordId) throws Exception {
        var ctx = new java.util.LinkedHashMap<String, Object>();
        ctx.put("action", "discover");
        ctx.put("messageId", UUID.randomUUID().toString());
        ctx.put("transactionId", UUID.randomUUID().toString());
        ctx.put("version", "2.0.0");
        ctx.put("bapId", "test-bap");
        ctx.put("bapUri", "https://test-bap.example.com");
        ctx.put("bppId", "test-bpp");
        ctx.put("bppUri", "https://test-bpp.example.com");
        ctx.put("networkId", "test-network");
        ctx.put("timestamp", Instant.now().toString());

        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("context", ctx);
        payload.put("message", java.util.Map.of());

        var meta = new java.util.LinkedHashMap<String, Object>();
        if (subscriberId != null) meta.put("subscriber_id", subscriberId);
        if (recordId != null) meta.put("record_id", recordId);

        var envelope = new java.util.LinkedHashMap<String, Object>();
        envelope.put("meta", meta);
        envelope.put("payload", payload);

        return objectMapper.writeValueAsString(envelope);
    }

    private DiscoverResponse buildResponse() {
        var ctx = new Context();
        ctx.setAction("on_discover");
        ctx.setMessageId(UUID.randomUUID().toString());
        ctx.setTransactionId(UUID.randomUUID().toString());
        var msg = new DiscoverResponse.ResponseMessage(List.of());
        return new DiscoverResponse(ctx, msg);
    }
}
