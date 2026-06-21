package org.beckn.discover.controller;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.beckn.discover.common.BecknFields;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.logging.BecknMdcContext;
import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.logging.MdcField;
import org.beckn.discover.model.AckResponse;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.model.DiscoverResponse;
import org.beckn.discover.service.DiscoveryService;
import org.beckn.discover.service.validation.DiscoveryValidationService;
import org.beckn.discover.service.authorization.AuthorizationService;
import org.beckn.discover.common.ErrorMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static net.logstash.logback.argument.StructuredArguments.value;

/**
 * Spring Boot REST controller for Beckn discovery API.
 *
 * <p>Entry point for {@code /beckn/discover}.</p>
 *
 * <p><b>Request Processing Pipeline:</b></p>
 * <ol>
 *   <li><b>Authorization:</b> Validates Beckn HTTP Signatures via {@link AuthorizationService}.</li>
 *   <li><b>Schema Validation:</b> Validates JSON structure via {@link DiscoveryValidationService}.</li>
 *   <li><b>Business Logic:</b> Propagates valid requests to {@link DiscoveryService}.</li>
 * </ol>
 *
 * <p>The {@code messageId} from the request context is stored as a servlet
 * request attribute ({@code "beckn.messageId"}) early in the pipeline so that
 * {@link org.beckn.discover.exception.GlobalExceptionHandler} can echo it in
 * NACK responses even when an exception is thrown before the request is parsed
 * into a {@link DiscoverRequest}.</p>
 */
@RestController
public class DiscoveryController {

    /** Request attribute key used to propagate the messageId to the exception handler for NACK bodies. */
    public static final String MESSAGE_ID_ATTR = "beckn.messageId";

    private static final Logger log = LoggerFactory.getLogger(DiscoveryController.class);

    private final DiscoveryService discoveryService;
    private final ObjectMapper objectMapper;
    private final DiscoveryValidationService validationService;
    private final AuthorizationService authorizationService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DiscoveryProperties discoveryProperties;
    private final ExecutorService queryExecutor;
    /** Short-lived cache keyed on messageId — suppresses duplicate Kafka publishes on BAP retries (M10). */
    private final Cache<String, Boolean> messageIdDedupCache;

    public DiscoveryController(
            DiscoveryService discoveryService,
            ObjectMapper objectMapper,
            DiscoveryValidationService validationService,
            AuthorizationService authorizationService,
            KafkaTemplate<String, String> kafkaTemplate,
            DiscoveryProperties discoveryProperties,
            @Qualifier("discoveryQueryExecutor") ExecutorService queryExecutor) {
        this.discoveryService = discoveryService;
        this.objectMapper = objectMapper;
        this.validationService = validationService;
        this.authorizationService = authorizationService;
        this.kafkaTemplate = kafkaTemplate;
        this.discoveryProperties = discoveryProperties;
        this.queryExecutor = queryExecutor;
        long dedupTtl = discoveryProperties.getKafka().getDedupCacheTtlSeconds();
        this.messageIdDedupCache = Caffeine.newBuilder()
                .expireAfterWrite(dedupTtl, TimeUnit.SECONDS)
                .maximumSize(10_000)
                .build();
    }

    /**
     * Synchronous discovery endpoint — returns the full on_discover response inline.
     */
    @GetMapping("/discover")
    public ResponseEntity<DiscoverResponse> discover(
            @RequestBody byte[] rawBytes,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest httpRequest) throws Exception {
        return handleDiscoverRequest(rawBytes, headers, httpRequest);
    }

    /**
     * POST endpoint for async Beckn discovery.
     *
     * <p>Performs the same auth and schema validation as the GET endpoint, then publishes
     * the request to the Kafka request topic and immediately returns an ACK.  The request
     * is processed asynchronously by {@link org.beckn.discover.consumer.DiscoveryEventConsumer},
     * which publishes the {@code on_discover} response to the response topic for the
     * response-dispatcher to forward to the BAP callback URL.</p>
     */
    @PostMapping("/discover")
    public ResponseEntity<AckResponse> discoverPost(
            @RequestBody byte[] rawBytes,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest httpRequest) throws Exception {
        return handleAsyncDiscoverRequest(rawBytes, headers, httpRequest);
    }

    /** Shared pipeline: authorize → validate → process. */
    private ResponseEntity<DiscoverResponse> handleDiscoverRequest(
            byte[] rawBytes, HttpHeaders headers, HttpServletRequest httpRequest) throws Exception {

        String rawBody = new String(rawBytes, StandardCharsets.UTF_8);
        JsonNode requestNode = objectMapper.readTree(rawBody);

        BecknMdcContext.setTagsFromHttp(httpRequest.getHeader("X-Tags"));
        JsonNode contextNode = requestNode.path(BecknFields.CONTEXT);
        BecknMdcContext.populate(contextNode);

        try {
            JsonNode txnNode = contextNode.path(BecknFields.TRANSACTION_ID);
            JsonNode msgIdNode = contextNode.path(BecknFields.MESSAGE_ID);
            if (msgIdNode.isTextual() && !msgIdNode.asText().isBlank()) {
                httpRequest.setAttribute(MESSAGE_ID_ATTR, msgIdNode.asText());
            }

            log.info(LogEvent.REQUEST_RECEIVED,
                    value("method", httpRequest.getMethod()),
                    value("transactionId", txnNode.asText("")));

            // Direct call on the GET path — the return value is not needed here (no Kafka
            // headers to set), and wrapping a blocking join in supplyAsync provides no
            // benefit when we have to wait for the result anyway.
            authorizationService.authorizeRequest(rawBody, headers);
            log.info(LogEvent.AUTH_PASSED);

            validateSchema(requestNode, rawBody);

            DiscoverRequest request = objectMapper.convertValue(requestNode, DiscoverRequest.class);
            DiscoverResponse result = discoveryService.processDiscoveryRequest(request);
            return ResponseEntity.ok(result);
        } finally {
            BecknMdcContext.clear();
        }
    }

    /** Async pipeline: authorize → validate → publish to Kafka → ACK. */
    private ResponseEntity<AckResponse> handleAsyncDiscoverRequest(
            byte[] rawBytes, HttpHeaders headers, HttpServletRequest httpRequest) throws Exception {

        String rawBody = new String(rawBytes, StandardCharsets.UTF_8);
        JsonNode requestNode = objectMapper.readTree(rawBody);

        BecknMdcContext.setTagsFromHttp(httpRequest.getHeader("X-Tags"));
        JsonNode contextNode = requestNode.path(BecknFields.CONTEXT);
        BecknMdcContext.populate(contextNode);

        try {
            String transactionId = null;
            JsonNode txnNode = contextNode.path(BecknFields.TRANSACTION_ID);
            if (txnNode.isTextual() && !txnNode.asText().isBlank()) {
                transactionId = txnNode.asText();
            }

            // Extract messageId early so the exception handler can echo it in NACK bodies
            // even when auth or validation throw before we reach the dedup check below.
            String messageId = contextNode.path(BecknFields.MESSAGE_ID).asText(null);
            if (messageId != null && !messageId.isBlank()) {
                httpRequest.setAttribute(MESSAGE_ID_ATTR, messageId);
            }

            log.info(LogEvent.REQUEST_RECEIVED,
                    value("method", "POST"),
                    value("transactionId", transactionId));

            // Move Ed25519 signature verification off the Tomcat thread so the server
            // thread is free during the crypto/registry-lookup operation (M9).
            // Capture the returned identity on the Tomcat thread — MDC is thread-local and
            // cannot be read across the executor-thread boundary (would always be null).
            var identity = joinUnwrapped(CompletableFuture.supplyAsync(
                    () -> authorizationService.authorizeRequest(rawBody, headers), queryExecutor));
            // Also apply to MDC on this thread so downstream log statements include auth fields.
            BecknMdcContext.setAuthFields(identity.subscriberId(), identity.recordId());
            log.info(LogEvent.AUTH_PASSED);

            validateSchema(requestNode, rawBody);

            // M10: Idempotency check — if same messageId was seen within dedupCacheTtlSeconds,
            // return ACK immediately without re-publishing to Kafka.
            if (messageId != null && !messageId.isBlank()
                    && messageIdDedupCache.getIfPresent(messageId) != null) {
                log.info(LogEvent.REQUEST_RECEIVED + ".duplicate-suppressed",
                        value("messageId", messageId),
                        value("transactionId", transactionId));
                return ResponseEntity.ok(AckResponse.ack(messageId));
            }

            String kafkaKey = transactionId != null ? transactionId : messageId;
            String requestTopic = discoveryProperties.getKafka().getRequestTopic();
            if (requestTopic == null || requestTopic.isBlank()) {
                throw new IllegalStateException(ErrorMessages.NET_INTERNAL_ERROR);
            }

            final String logTxnId = transactionId;
            final String logMsgId = messageId;
            try {
                var kafkaHeaders = new RecordHeaders();
                // Tags travel as Kafka header (standard cross-job propagation); identity travels
                // in JSON meta body — consistent with Catalg catalogService.publishCatalog pattern.
                addHeaderIfPresent(kafkaHeaders, MdcField.TAGS, MDC.get(MdcField.TAGS));

                // Wrap the discover request in an envelope carrying caller identity in meta.
                // Format: { "meta": { "subscriber_id": "...", "record_id": "..." }, "payload": { <discover request> } }
                // meta is internal routing only — stripped by DiscoveryEventConsumer before processing.
                ObjectNode envelope = objectMapper.createObjectNode();
                ObjectNode metaNode = envelope.putObject(BecknFields.META);
                metaNode.put(BecknFields.SUBSCRIBER_ID, identity.subscriberId());
                metaNode.put(BecknFields.RECORD_ID, identity.recordId());
                envelope.set(BecknFields.PAYLOAD, requestNode);
                String kafkaBody = objectMapper.writeValueAsString(envelope);

                var record = new ProducerRecord<>(requestTopic, null, kafkaKey, kafkaBody, kafkaHeaders);
                // Record the messageId before sending — ensures the cache entry is set before
                // any concurrent retry arrives, even if the send completes asynchronously.
                if (logMsgId != null && !logMsgId.isBlank()) {
                    messageIdDedupCache.put(logMsgId, Boolean.TRUE);
                }
                kafkaTemplate.send(record)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error(LogEvent.KAFKA_QUEUE_FAILED,
                                        value("transactionId", logTxnId),
                                        value("messageId", logMsgId),
                                        value("topic", requestTopic),
                                        ex);
                            } else {
                                log.debug(LogEvent.KAFKA_QUEUED,
                                        value("transactionId", logTxnId),
                                        value("messageId", logMsgId),
                                        value("topic", requestTopic),
                                        value("partition", result.getRecordMetadata().partition()),
                                        value("offset", result.getRecordMetadata().offset()));
                            }
                        });
            } catch (Exception kafkaEx) {
                log.error(LogEvent.KAFKA_QUEUE_FAILED,
                        value("transactionId", logTxnId),
                        value("messageId", logMsgId),
                        value("topic", requestTopic),
                        value("error", kafkaEx.getMessage()));
            }

            return ResponseEntity.ok(AckResponse.ack(messageId));
        } finally {
            BecknMdcContext.clear();
        }
    }

    private void validateSchema(JsonNode requestNode, String rawBody) {
        log.info(LogEvent.VALIDATE_STARTING);
        DiscoveryValidationService.ValidationResult result = validationService.validateDiscoverRequest(requestNode);
        if (!result.isValid()) {
            String msg = "Schema validation failed: " + String.join("; ", result.getErrors());
            log.warn(LogEvent.VALIDATE_FAILED,
                    value("errors", result.getErrors()),
                    value("paths", result.getPaths()),
                    value("requestBody", truncate(rawBody, 2000)));
            throw new IllegalArgumentException(msg);
        }
        log.info(LogEvent.VALIDATE_PASSED);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...[truncated]";
    }

    private static void addHeaderIfPresent(RecordHeaders headers, String key, String value) {
        if (value != null && !value.isBlank()) {
            headers.add(key, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Joins a {@link CompletableFuture} and rethrows any exception unwrapped from
     * {@link CompletionException}, so that the original exception type (e.g. {@link
     * org.springframework.web.ErrorResponseException}) reaches the
     * {@link org.beckn.discover.exception.GlobalExceptionHandler} with the correct
     * HTTP status code instead of being swallowed into a 500.
     */
    private static <T> T joinUnwrapped(CompletableFuture<T> future) throws Exception {
        try {
            return future.join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof Exception e) {
                throw e;
            }
            throw ce;
        }
    }
}
