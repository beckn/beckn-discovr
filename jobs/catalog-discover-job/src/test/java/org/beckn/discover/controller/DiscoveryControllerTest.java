package org.beckn.discover.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.beckn.auth.exception.BecknAuthException;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.exception.GlobalExceptionHandler;
import org.beckn.discover.model.AckResponseFactory;
import org.beckn.discover.service.DiscoveryService;
import org.beckn.discover.service.authorization.AuthorizationService;
import org.beckn.discover.service.authorization.AuthorizationService.AuthIdentity;
import org.beckn.discover.service.validation.DiscoveryValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.ErrorResponseException;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for the async POST /beckn/discover path in {@link DiscoveryController}.
 *
 * <p>Scenarios covered:
 * <ol>
 *   <li>Happy path — AuthorizationService returns a named identity;
 *       {@code subscriber_id} and {@code record_id} are embedded in the JSON meta
 *       envelope body of the {@code ProducerRecord} (no Kafka headers for identity).</li>
 *   <li>Anonymous identity — AuthorizationService returns
 *       {@link AuthIdentity#anonymous()}; the envelope meta carries {@code "anonymous"}
 *       values and the original discover request is in {@code payload}.</li>
 *   <li>Auth failure — AuthorizationService throws
 *       {@link ErrorResponseException} with HTTP 401; no Kafka send is issued and
 *       the controller returns 401.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryControllerTest {

    private static final String DISCOVER_PATH = "/discover";

    @Mock private DiscoveryService discoveryService;
    @Mock private DiscoveryValidationService validationService;
    @Mock private AuthorizationService authorizationService;
    @SuppressWarnings("rawtypes")
    @Mock private KafkaTemplate kafkaTemplate;

    /** Default (Beckn v2.0 nested shape, legacy-ack-nack-support=false). */
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        // Schema validation always passes — lenient because auth-failure test never reaches validation
        var validResult = mock(DiscoveryValidationService.ValidationResult.class);
        lenient().when(validResult.isValid()).thenReturn(true);
        lenient().when(validationService.validateDiscoverRequest(any(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(validResult);

        // KafkaTemplate returns a completed future — lenient because auth-failure test never sends
        var sendResult = mock(org.springframework.kafka.support.SendResult.class);
        var metadata = mock(org.apache.kafka.clients.producer.RecordMetadata.class);
        lenient().when(sendResult.getRecordMetadata()).thenReturn(metadata);
        lenient().when(metadata.partition()).thenReturn(0);
        lenient().when(metadata.offset()).thenReturn(0L);
        lenient()
                .when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        mockMvc = buildMockMvc(false);
    }

    /**
     * Builds a stand-alone MockMvc wired with an {@link AckResponseFactory} configured for the
     * given {@code legacyAckNackSupport} flag. The same factory instance backs both the controller
     * (ACK) and the {@link GlobalExceptionHandler} (NACK) so both response construction sites honour
     * the flag through the single decision point.
     */
    @SuppressWarnings("unchecked")
    private MockMvc buildMockMvc(boolean legacyAckNackSupport) {
        var props = new DiscoveryProperties();
        props.getKafka().setRequestTopic("test.discover.requests");
        props.getKafka().setResponseTopic("test.discover.responses");
        props.getKafka().setDedupCacheTtlSeconds(60);
        props.setLegacyAckNackSupport(legacyAckNackSupport);

        var ackResponseFactory = new AckResponseFactory(props);
        var executor = Executors.newFixedThreadPool(2);

        var controller = new DiscoveryController(
                discoveryService,
                objectMapper,
                validationService,
                org.mockito.Mockito.mock(org.beckn.discover.service.validation.IntentQueryValidator.class),
                authorizationService,
                (KafkaTemplate<String, String>) kafkaTemplate,
                props,
                executor,
                ackResponseFactory);

        // Register GlobalExceptionHandler so auth failures produce correct HTTP status codes
        return MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(ackResponseFactory))
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void postDiscover_namedIdentity_metaEnvelopeCarriesSubscriberIdAndRecordId() throws Exception {
        // Auth returns a concrete identity — must be in the JSON meta body, NOT Kafka headers
        doReturn(new AuthIdentity("bpp.seller.io", "bpp-key-001"))
                .when(authorizationService).authorizeRequest(any(), any());

        String messageId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        mockMvc.perform(post(DISCOVER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPayload(messageId, transactionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.status").value("ACK"))
                .andExpect(jsonPath("$.message.messageId").value(messageId))
                .andExpect(jsonPath("$.message.transactionId").doesNotExist());

        @SuppressWarnings("rawtypes")
        var captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(1)).send(captor.capture());

        ProducerRecord<String, String> record = captor.getValue();

        // Identity travels in JSON meta body — no Kafka headers for subscriber_id / record_id
        assertThat(record.headers().lastHeader("subscriber_id")).isNull();
        assertThat(record.headers().lastHeader("record_id")).isNull();

        // Meta envelope carries the identity; payload carries the original discover request
        var envelope = objectMapper.readTree(record.value());
        assertThat(envelope.path("meta").path("subscriber_id").asText()).isEqualTo("bpp.seller.io");
        assertThat(envelope.path("meta").path("record_id").asText()).isEqualTo("bpp-key-001");
        assertThat(envelope.path("payload").path("context").path("action").asText()).isEqualTo("discover");
    }

    @Test
    @SuppressWarnings("unchecked")
    void postDiscover_anonymousIdentity_metaEnvelopeCarriesAnonymousValues() throws Exception {
        // Auth disabled — returns anonymous; envelope meta must still carry "anonymous" values
        doReturn(AuthIdentity.anonymous())
                .when(authorizationService).authorizeRequest(any(), any());

        String messageId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        mockMvc.perform(post(DISCOVER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPayload(messageId, transactionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.status").value("ACK"))
                .andExpect(jsonPath("$.message.messageId").value(messageId))
                .andExpect(jsonPath("$.message.transactionId").doesNotExist());

        @SuppressWarnings("rawtypes")
        var captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(1)).send(captor.capture());

        ProducerRecord<String, String> record = captor.getValue();

        // Identity in JSON meta body — no Kafka headers for subscriber_id / record_id
        assertThat(record.headers().lastHeader("subscriber_id")).isNull();
        assertThat(record.headers().lastHeader("record_id")).isNull();

        var envelope = objectMapper.readTree(record.value());
        assertThat(envelope.path("meta").path("subscriber_id").asText()).isEqualTo("anonymous");
        assertThat(envelope.path("meta").path("record_id").asText()).isEqualTo("anonymous");
        assertThat(envelope.path("payload").path("context").path("action").asText()).isEqualTo("discover");
    }

    @Test
    void postDiscover_authFailure_returns401AndNoKafkaSend() throws Exception {
        // Auth throws ErrorResponseException (401) — no message must reach Kafka
        var pd = ProblemDetail.forStatus(401);
        pd.setDetail("Signature verification failed");
        // AuthorizationService surfaces the spec AUT_* code (translated from the SDK's
        // SEC_* code, which remains on the underlying BecknAuthException cause).
        pd.setProperty("code", "AUT_SIGNATURE_INVALID");
        doThrow(new ErrorResponseException(HttpStatusCode.valueOf(401), pd,
                BecknAuthException.signatureVerificationFailed("bad sig", "SEC_SIGNATURE_INVALID")))
                .when(authorizationService).authorizeRequest(any(), any());

        String messageId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        mockMvc.perform(post(DISCOVER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPayload(messageId, transactionId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message.status").value("NACK"))
                .andExpect(jsonPath("$.message.error.code").value("AUT_SIGNATURE_INVALID"))
                .andExpect(jsonPath("$.message.messageId").value(messageId))
                .andExpect(jsonPath("$.message.transactionId").doesNotExist());

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    // ── legacy-ack-nack-support flag: response-shape switching (issue #406 / #408) ──────────

    /**
     * Guard test: the default MUST be false so the out-of-the-box shape stays Beckn v2.0 nested.
     * Fails loudly if someone flips the {@code discovery.legacy-ack-nack-support} default.
     */
    @Test
    void legacyAckNackSupport_defaultsToFalse() {
        assertThat(new DiscoveryProperties().isLegacyAckNackSupport()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void postDiscover_flagFalse_ack_usesV20NestedShape() throws Exception {
        doReturn(new AuthIdentity("bpp.seller.io", "bpp-key-001"))
                .when(authorizationService).authorizeRequest(any(), any());

        String messageId = UUID.randomUUID().toString();
        // Default mockMvc is built with legacyAckNackSupport=false
        mockMvc.perform(post(DISCOVER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPayload(messageId, UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                // v2.0 nested envelope
                .andExpect(jsonPath("$.message.status").value("ACK"))
                .andExpect(jsonPath("$.message.messageId").value(messageId))
                // legacy flat fields MUST be absent
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @SuppressWarnings("unchecked")
    void postDiscover_flagTrue_ack_usesLegacyFlatShape() throws Exception {
        doReturn(new AuthIdentity("bpp.seller.io", "bpp-key-001"))
                .when(authorizationService).authorizeRequest(any(), any());

        MockMvc legacyMvc = buildMockMvc(true);

        String messageId = UUID.randomUUID().toString();
        legacyMvc.perform(post(DISCOVER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPayload(messageId, UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                // legacy flat: root-level status, no message wrapper, no messageId
                .andExpect(jsonPath("$.status").value("ACK"))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.messageId").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void postDiscover_flagFalse_nack_usesV20NestedShape() throws Exception {
        var pd = ProblemDetail.forStatus(401);
        pd.setDetail("Signature verification failed");
        pd.setProperty("code", "AUT_SIGNATURE_INVALID");
        doThrow(new ErrorResponseException(HttpStatusCode.valueOf(401), pd,
                BecknAuthException.signatureVerificationFailed("bad sig", "SEC_SIGNATURE_INVALID")))
                .when(authorizationService).authorizeRequest(any(), any());

        String messageId = UUID.randomUUID().toString();
        // Default mockMvc — legacyAckNackSupport=false
        mockMvc.perform(post(DISCOVER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPayload(messageId, UUID.randomUUID().toString())))
                .andExpect(status().isUnauthorized())
                // v2.0 nested envelope: code/message under message.error, echoes messageId
                .andExpect(jsonPath("$.message.status").value("NACK"))
                .andExpect(jsonPath("$.message.messageId").value(messageId))
                .andExpect(jsonPath("$.message.error.code").value("AUT_SIGNATURE_INVALID"))
                .andExpect(jsonPath("$.message.error.message").value("Signature verification failed"))
                // legacy flat fields MUST be absent
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void postDiscover_flagTrue_nack_usesLegacyFlatShape() throws Exception {
        var pd = ProblemDetail.forStatus(401);
        pd.setDetail("Signature verification failed");
        pd.setProperty("code", "AUT_SIGNATURE_INVALID");
        doThrow(new ErrorResponseException(HttpStatusCode.valueOf(401), pd,
                BecknAuthException.signatureVerificationFailed("bad sig", "SEC_SIGNATURE_INVALID")))
                .when(authorizationService).authorizeRequest(any(), any());

        MockMvc legacyMvc = buildMockMvc(true);

        String messageId = UUID.randomUUID().toString();
        legacyMvc.perform(post(DISCOVER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildPayload(messageId, UUID.randomUUID().toString())))
                .andExpect(status().isUnauthorized())
                // legacy flat: root-level status + error{errorCode,errorMessage}, no message, no messageId.
                // The code VALUE and message VALUE are identical to v2.0 — only the envelope/field names change.
                .andExpect(jsonPath("$.status").value("NACK"))
                .andExpect(jsonPath("$.error.errorCode").value("AUT_SIGNATURE_INVALID"))
                .andExpect(jsonPath("$.error.errorMessage").value("Signature verification failed"))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.messageId").doesNotExist())
                // v2.0 field names MUST be absent
                .andExpect(jsonPath("$.error.code").doesNotExist())
                .andExpect(jsonPath("$.error.message").doesNotExist());
    }

    /**
     * Legacy mode, malformed-JSON NACK path ({@code handleMalformedJson}). This exercises a
     * different exception site than the auth-failure tests above (which go through
     * {@code buildErrorResponse}), and it is the only NACK path where {@code messageId} is
     * genuinely unrecoverable — the body never parses, so {@code context.messageId} is never
     * captured. Asserts the flat envelope AND that {@code messageId} is dropped, never fabricated.
     */
    @Test
    void postDiscover_flagTrue_malformedJson_usesLegacyFlatShape_withoutMessageId() throws Exception {
        MockMvc legacyMvc = buildMockMvc(true);

        legacyMvc.perform(post(DISCOVER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not valid json "))
                .andExpect(status().isBadRequest())
                // legacy flat: root-level status + error{errorCode,errorMessage}
                .andExpect(jsonPath("$.status").value("NACK"))
                .andExpect(jsonPath("$.error.errorCode").value("SCH_INVALID_JSON"))
                .andExpect(jsonPath("$.error.errorMessage").isNotEmpty())
                // unparseable body → messageId unrecoverable → dropped (not fabricated)
                .andExpect(jsonPath("$.messageId").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist())
                // v2.0 field names MUST be absent
                .andExpect(jsonPath("$.error.code").doesNotExist())
                .andExpect(jsonPath("$.error.message").doesNotExist());
    }

    /**
     * Default mode, same malformed-JSON path — the v2.0 nested counterpart. Confirms the shared
     * factory keeps the nested envelope here too, and that {@code messageId} is omitted (not null,
     * not fabricated) when the body is unparseable.
     */
    @Test
    void postDiscover_flagFalse_malformedJson_usesV20NestedShape_withoutMessageId() throws Exception {
        // Default mockMvc — legacyAckNackSupport=false
        mockMvc.perform(post(DISCOVER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not valid json "))
                .andExpect(status().isBadRequest())
                // v2.0 nested envelope
                .andExpect(jsonPath("$.message.status").value("NACK"))
                .andExpect(jsonPath("$.message.error.code").value("SCH_INVALID_JSON"))
                .andExpect(jsonPath("$.message.error.message").isNotEmpty())
                .andExpect(jsonPath("$.message.messageId").doesNotExist())
                // legacy flat fields MUST be absent
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
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
