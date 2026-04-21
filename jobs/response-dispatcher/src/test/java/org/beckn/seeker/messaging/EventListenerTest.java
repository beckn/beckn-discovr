package org.beckn.seeker.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.beckn.seeker.messaging.consumer.EventListener;
import org.beckn.seeker.messaging.producer.EventProducer;
import org.beckn.seeker.service.MessageProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventListenerTest {

    @Mock
    private EventProducer eventProducer;

    @Mock
    private MessageProcessingService messageProcessingService;

    @Mock
    private Acknowledgment acknowledgment;

    private EventListener eventListener;

    @BeforeEach
    void setUp() {
        // Use the real ObjectMapper — EventListener only uses it to parse context for MDC
        eventListener = new EventListener(eventProducer, messageProcessingService, new ObjectMapper());
    }

    @Test
    void shouldProcessDiscoveryResponseSuccessfully() {
        // Given - Beckn v2.0 camelCase context fields
        String testMessage = """
            {
              "context": {
                "messageId": "msg-123",
                "bapUri": "https://bap.example.com",
                "action": "on_search"
              },
              "catalog": {
                "providers": []
              }
            }
            """;
        String processedResult = "SUCCESS";

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "catalog.discovery.response", 0, 0L, "original-key", testMessage);

        when(messageProcessingService.processMessage(eq(testMessage), isNull(), isNull())).thenReturn(processedResult);

        // When
        eventListener.listen(record, acknowledgment);

        // Then
        verify(messageProcessingService).processMessage(eq(testMessage), isNull(), isNull());
        verify(acknowledgment).acknowledge();
        verifyNoMoreInteractions(eventProducer);
    }

    @Test
    void shouldUseExtractedKeyWhenOriginalKeyIsNull() {
        // Given - Beckn v2.0 camelCase context fields
        String testMessage = """
            {
              "context": {
                "messageId": "msg-456",
                "bapUri": "https://bap.example.com",
                "action": "on_search"
              },
              "catalog": {
                "providers": []
              }
            }
            """;
        String processedResult = "SUCCESS";

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "catalog.discovery.response", 0, 0L, null, testMessage);

        when(messageProcessingService.processMessage(eq(testMessage), isNull(), isNull())).thenReturn(processedResult);

        // When
        eventListener.listen(record, acknowledgment);

        // Then
        verify(messageProcessingService).processMessage(eq(testMessage), isNull(), isNull());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldSendToDltOnProcessingError() {
        // Given - Beckn v2.0 camelCase context fields
        String testMessage = """
            {
              "context": {
                "messageId": "msg-123",
                "bapUri": "https://bap.example.com",
                "action": "on_search"
              },
              "catalog": {
                "providers": []
              }
            }
            """;
        String errorMessage = "Connection timeout";
        RuntimeException processingException = new RuntimeException(errorMessage);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "catalog.discovery.response", 1, 5L, "test-key", testMessage);

        when(messageProcessingService.processMessage(eq(testMessage), isNull(), isNull())).thenThrow(processingException);

        // When
        eventListener.listen(record, acknowledgment);

        // Then
        verify(messageProcessingService).processMessage(eq(testMessage), isNull(), isNull());
        verify(eventProducer).sendToDlt(
                "test-key",
                testMessage,
                "catalog.discovery.response",
                1,
                5L,
                errorMessage,
                RuntimeException.class.getName()
        );
        verify(acknowledgment).acknowledge();
    }


    @Test
    void shouldExtractIdentityHeadersAndPassToProcessing() {
        // Given — Kafka record with subscriber_id and record_id headers
        String testMessage = """
            {
              "context": {
                "messageId": "msg-789",
                "action": "on_discover"
              },
              "catalog": {
                "providers": []
              }
            }
            """;

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "catalog.discovery.response", 0, 0L, "key", testMessage);
        record.headers().add(new RecordHeader("subscriber_id",
                "bap.example.com".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("record_id",
                "key-001".getBytes(StandardCharsets.UTF_8)));

        when(messageProcessingService.processMessage(
                eq(testMessage), eq("bap.example.com"), eq("key-001")))
                .thenReturn("SUCCESS");

        // When
        eventListener.listen(record, acknowledgment);

        // Then — identity headers extracted and forwarded
        verify(messageProcessingService).processMessage(
                eq(testMessage), eq("bap.example.com"), eq("key-001"));
        verify(acknowledgment).acknowledge();
        verifyNoMoreInteractions(eventProducer);
    }

    @Test
    void shouldHandleNullMessage() {
        // Given
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "test-topic", 0, 0L, "test-key", null);

        when(messageProcessingService.processMessage(isNull(), isNull(), isNull()))
                .thenThrow(new IllegalArgumentException("Message cannot be null"));

        // When
        eventListener.listen(record, acknowledgment);

        // Then
        verify(eventProducer).sendToDlt(
                eq("test-key"),
                isNull(),
                eq("test-topic"),
                eq(0),
                eq(0L),
                eq("Message cannot be null"),
                eq(IllegalArgumentException.class.getName())
        );
        verify(acknowledgment).acknowledge();
    }
}
