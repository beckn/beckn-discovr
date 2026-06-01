package org.beckn.discover.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.service.DiscoveryService;
import org.beckn.discover.service.authorization.AuthorizationService;
import org.beckn.discover.service.validation.DiscoveryValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit test for M10: messageId idempotency cache on POST /beckn/discover.
 *
 * <p>Verifies that a duplicate POST with the same {@code messageId} within the
 * dedup TTL window returns ACK immediately without re-publishing to Kafka.</p>
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryControllerDedupTest {

    private static final String DISCOVER_PATH = "/discover";

    @Mock private DiscoveryService discoveryService;
    @Mock private DiscoveryValidationService validationService;
    @Mock private AuthorizationService authorizationService;
    @SuppressWarnings("rawtypes")
    @Mock private KafkaTemplate kafkaTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        var props = new DiscoveryProperties();
        props.getKafka().setRequestTopic("test.discover.requests");
        props.getKafka().setResponseTopic("test.discover.responses");
        props.getKafka().setDedupCacheTtlSeconds(60);

        // Auth is disabled (default) — authorizeRequest returns anonymous identity
        doReturn(AuthorizationService.AuthIdentity.anonymous())
                .when(authorizationService).authorizeRequest(any(), any());

        // Schema validation always passes
        var validResult = mock(DiscoveryValidationService.ValidationResult.class);
        when(validResult.isValid()).thenReturn(true);
        when(validationService.validateDiscoverRequest(any(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(validResult);

        // KafkaTemplate returns a completed future
        var sendResult = mock(org.springframework.kafka.support.SendResult.class);
        var metadata = mock(org.apache.kafka.clients.producer.RecordMetadata.class);
        when(sendResult.getRecordMetadata()).thenReturn(metadata);
        when(metadata.partition()).thenReturn(0);
        when(metadata.offset()).thenReturn(0L);
        when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        var executor = Executors.newFixedThreadPool(2);

        @SuppressWarnings("unchecked")
        var controller = new DiscoveryController(
                discoveryService,
                new ObjectMapper(),
                validationService,
                authorizationService,
                (KafkaTemplate<String, String>) kafkaTemplate,
                props,
                executor);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void postDiscover_sameMessageIdTwice_kafkaPublishedOnlyOnce() throws Exception {
        String messageId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String payload = buildPayload(messageId, transactionId);

        // First request — should publish to Kafka
        mockMvc.perform(post(DISCOVER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACK"));

        // Second request with same messageId — should be deduplicated
        mockMvc.perform(post(DISCOVER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACK"));

        // Kafka must have been called exactly once
        verify(kafkaTemplate, times(1)).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
    }

    @Test
    void postDiscover_differentMessageIds_kafkaPublishedTwice() throws Exception {
        String transactionId = UUID.randomUUID().toString();

        String payload1 = buildPayload(UUID.randomUUID().toString(), transactionId);
        String payload2 = buildPayload(UUID.randomUUID().toString(), transactionId);

        mockMvc.perform(post(DISCOVER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACK"));

        mockMvc.perform(post(DISCOVER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACK"));

        // Two distinct messageIds — Kafka must receive both
        verify(kafkaTemplate, times(2)).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
    }

    private static String buildPayload(String messageId, String transactionId) {
        return String.format("""
                {
                  "context": {
                    "action": "discover",
                    "version": "2.0.0",
                    "messageId": "%s",
                    "transactionId": "%s",
                    "bapId": "bap.test.io",
                    "bapUri": "https://bap.test.io",
                    "bppId": "bpp.test.io",
                    "bppUri": "https://bpp.test.io",
                    "networkId": "test-network",
                    "timestamp": "2025-01-01T00:00:00Z"
                  },
                  "message": {
                    "intent": {
                      "textSearch": "EV charger"
                    }
                  }
                }
                """, messageId, transactionId);
    }
}
