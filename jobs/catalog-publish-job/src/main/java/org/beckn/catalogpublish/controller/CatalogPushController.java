package org.beckn.catalogpublish.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.common.ErrorCodes;
import org.beckn.catalogpublish.common.ErrorMessages;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.util.CorrelationContext;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * POST /catalog/push — Beckn subscriber callback endpoint.
 *
 * <p>Accepts a catalog payload from a BPP, returns {@code 202 Accepted}
 * immediately, and processes the catalog asynchronously through the
 * existing publish pipeline.</p>
 */
@RestController
public class CatalogPushController {

    private static final Logger log = LoggerFactory.getLogger(CatalogPushController.class);

    private final CatalogPushService pushService;
    private final ObjectMapper objectMapper;
    private final CorrelationContext correlationContext;
    private final long maxPayloadSize;

    public CatalogPushController(CatalogPushService pushService, AppProperties props,
            ObjectMapper objectMapper, CorrelationContext correlationContext) {
        this.pushService = pushService;
        this.maxPayloadSize = props.catalog().maxPayloadSize();
        this.objectMapper = objectMapper;
        this.correlationContext = correlationContext;
    }

    @PostMapping("/catalog/push")
    public ResponseEntity<Map<String, Object>> push(
            @RequestBody byte[] rawBytes,
            HttpServletRequest request) {

        if (rawBytes.length > maxPayloadSize) {
            log.warn("event={} sizeBytes={} limit={}", LogEvent.PUSH_REJECTED, rawBytes.length, maxPayloadSize);
            // Payload not parsed — no messageId recoverable; omit messageId key.
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
                    nackBody(null, ErrorCodes.REQUEST_TOO_LARGE, ErrorMessages.REQUEST_TOO_LARGE));
        }

        String rawBody = new String(rawBytes, StandardCharsets.UTF_8);

        correlationContext.setTagsFromHttp(request.getHeader("X-Tags"));

        // Validate required context fields — reject with NACK if missing
        String messageId = extractMessageId(rawBody);
        if (!hasRequiredContext(rawBody, messageId)) {
            return ResponseEntity.badRequest().body(
                    nackBody(messageId, ErrorCodes.CTX_INVALID_FIELD, ErrorMessages.CTX_INVALID_FIELD));
        }

        log.info("event={} sizeBytes={}", LogEvent.PUSH_RECEIVED, rawBytes.length);
        pushService.enqueueForProcessing(rawBody);

        return ResponseEntity.accepted().body(ackBody(messageId));
    }

    /**
     * Builds a spec-compliant ACK body:
     * {@code {"message":{"status":"ACK","messageId":"<id>"}}}
     * When {@code messageId} is null the key is omitted.
     */
    static Map<String, Object> ackBody(String messageId) {
        Map<String, Object> inner = new HashMap<>();
        inner.put(BecknFields.STATUS, "ACK");
        if (messageId != null && !messageId.isBlank()) {
            inner.put(BecknFields.MESSAGE_ID, messageId);
        }
        Map<String, Object> outer = new HashMap<>();
        outer.put(BecknFields.MESSAGE, inner);
        return outer;
    }

    /**
     * Builds a spec-compliant NACK body:
     * {@code {"message":{"status":"NACK","messageId":"<id>","error":{"code":"...","message":"..."}}}}
     * When {@code messageId} is null the key is omitted.
     */
    static Map<String, Object> nackBody(String messageId, String code, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put(BecknFields.CODE, code);
        error.put(BecknFields.MESSAGE, message);

        Map<String, Object> inner = new HashMap<>();
        inner.put(BecknFields.STATUS, "NACK");
        if (messageId != null && !messageId.isBlank()) {
            inner.put(BecknFields.MESSAGE_ID, messageId);
        }
        inner.put(BecknFields.ERROR, error);

        Map<String, Object> outer = new HashMap<>();
        outer.put(BecknFields.MESSAGE, inner);
        return outer;
    }

    /**
     * Best-effort extraction of {@code context.messageId} from the raw body.
     * Returns {@code null} on any parse failure.
     */
    private String extractMessageId(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode ctx = root.path(BecknFields.CONTEXT);
            if (ctx.isMissingNode() || !ctx.isObject()) return null;
            JsonNode msgId = ctx.path(BecknFields.MESSAGE_ID);
            if (!msgId.isMissingNode() && msgId.isTextual() && !msgId.asText("").isBlank()) {
                return msgId.asText();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Validates that the payload has a valid context object with at least one
     * mandatory Beckn correlation field (messageId or transactionId).
     * No enrichment or fallback — callers must send a complete Beckn context.
     */
    private boolean hasRequiredContext(String rawBody, String alreadyExtractedMessageId) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode ctx = root.path(BecknFields.CONTEXT);
            if (ctx.isMissingNode() || !ctx.isObject()) {
                log.warn("event={} reason=missing-context", LogEvent.PUSH_REJECTED);
                return false;
            }
            boolean hasMessageId = alreadyExtractedMessageId != null;
            boolean hasTransactionId = !ctx.path(BecknFields.TRANSACTION_ID).isMissingNode()
                    && !ctx.path(BecknFields.TRANSACTION_ID).asText("").isBlank();
            if (!hasMessageId && !hasTransactionId) {
                log.warn("event={} reason=missing-correlation-id", LogEvent.PUSH_REJECTED);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("event={} reason=invalid-json error={}", LogEvent.PUSH_REJECTED, ErrorSanitizer.sanitize(e));
            return false;
        }
    }
}
