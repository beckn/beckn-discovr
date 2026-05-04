package org.beckn.discover.controller;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * <p>The {@code transaction_id} from the request context is stored as a servlet
 * request attribute ({@code "beckn.transactionId"}) early in the pipeline so that
 * {@link org.beckn.discover.exception.GlobalExceptionHandler} can include it in
 * NACK responses even when an exception is thrown before the request is parsed
 * into a {@link DiscoverRequest}.</p>
 */
@RestController
@RequestMapping("/beckn")
public class DiscoveryController {

    /** Request attribute key used to propagate the transaction ID to the exception handler. */
    public static final String TRANSACTION_ID_ATTR = "beckn.transactionId";

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

    /** GET endpoint for Beckn discovery. */
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
            if (txnNode.isTextual() && !txnNode.asText().isBlank()) {
                httpRequest.setAttribute(TRANSACTION_ID_ATTR, txnNode.asText());
            }

            log.info(LogEvent.REQUEST_RECEIVED,
                    value("method", httpRequest.getMethod()),
                    value("transactionId", txnNode.asText("")));

            // Move Ed25519 signature verification off the Tomcat thread so the server
            // thread is free during the crypto/registry-lookup operation (M9).
            CompletableFuture.runAsync(() -> authorizationService.authorizeRequest(rawBody, headers),
                    queryExecutor).join();
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
                httpRequest.setAttribute(TRANSACTION_ID_ATTR, transactionId);
            }

            log.info(LogEvent.REQUEST_RECEIVED,
                    value("method", "POST"),
                    value("transactionId", transactionId));

            // Move Ed25519 signature verification off the Tomcat thread so the server
            // thread is free during the crypto/registry-lookup operation (M9).
            CompletableFuture.runAsync(() -> authorizationService.authorizeRequest(rawBody, headers),
                    queryExecutor).join();
            log.info(LogEvent.AUTH_PASSED);

            validateSchema(requestNode, rawBody);

            String messageId = contextNode.path(BecknFields.MESSAGE_ID).asText();

            // M10: Idempotency check — if same messageId was seen within dedupCacheTtlSeconds,
            // return ACK immediately without re-publishing to Kafka.
            if (messageId != null && !messageId.isBlank()
                    && messageIdDedupCache.getIfPresent(messageId) != null) {
                log.info(LogEvent.REQUEST_RECEIVED + ".duplicate-suppressed",
                        value("messageId", messageId),
                        value("transactionId", transactionId));
                return ResponseEntity.ok(AckResponse.ack());
            }

            String kafkaKey = transactionId != null ? transactionId : messageId;
            String requestTopic = discoveryProperties.getKafka().getRequestTopic();
            if (requestTopic == null || requestTopic.isBlank()) {
                throw new IllegalStateException("discovery.kafka.request-topic is not configured");
            }

            final String logTxnId = transactionId;
            final String logMsgId = messageId;
            try {
                var kafkaHeaders = new RecordHeaders();
                addHeaderIfPresent(kafkaHeaders, "subscriber_id", MDC.get(MdcField.AUTH_SUBSCRIBER_ID));
                addHeaderIfPresent(kafkaHeaders, "record_id", MDC.get(MdcField.AUTH_RECORD_ID));
                addHeaderIfPresent(kafkaHeaders, "tags", MDC.get(MdcField.TAGS));

                var record = new ProducerRecord<>(requestTopic, null, kafkaKey, rawBody, kafkaHeaders);
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

            return ResponseEntity.ok(AckResponse.ack());
        } finally {
            BecknMdcContext.clear();
        }
    }

    private void validateSchema(JsonNode requestNode, String rawBody) {
        log.info(LogEvent.VALIDATE_PASSED + ".starting");
        DiscoveryValidationService.ValidationResult result = validationService.validateDiscoverRequest(requestNode);
        if (!result.isValid()) {
            String paths = result.getPaths().isEmpty() ? "root" : String.join(", ", result.getPaths());
            String msg = "Schema validation failed: " + String.join("; ", result.getErrors()) + " (paths: " + paths + ")";
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
}
