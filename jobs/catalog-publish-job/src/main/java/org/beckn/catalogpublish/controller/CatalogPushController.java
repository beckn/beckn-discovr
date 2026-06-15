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
 * <p>Accepts a catalog payload from a BPP, returns {@code 200 Ack}
 * immediately, and processes the catalog asynchronously through the
 * existing publish pipeline. A {@code catalog/on_publish} callback follows,
 * so the synchronous response is a Beckn {@code Ack} (200), not
 * {@code AckNoCallback} (202).</p>
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
            // An oversized body is a client error → 400 NackBadRequest. 413 is not part of the
            // Beckn response set (200/400/401/429/500). Payload not parsed — no correlation ids
            // recoverable; messageId/transactionId omitted.
            return ResponseEntity.badRequest().body(
                    nackBody(null, null, ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED, ErrorMessages.REQUEST_TOO_LARGE));
        }

        String rawBody = new String(rawBytes, StandardCharsets.UTF_8);

        correlationContext.setTagsFromHttp(request.getHeader("X-Tags"));

        // Parse once; reuse the tree for correlation-id extraction and context validation.
        JsonNode root = tryParse(rawBody);
        if (root == null) {
            // Unparseable JSON — distinct from a missing context. No correlation ids recoverable.
            return ResponseEntity.badRequest().body(
                    nackBody(null, null, ErrorCodes.SCH_INVALID_JSON, ErrorMessages.SCH_INVALID_JSON));
        }

        String messageId = contextText(root, BecknFields.MESSAGE_ID);
        String transactionId = contextText(root, BecknFields.TRANSACTION_ID);

        // Validate required context — reject with NACK if missing. The NACK still echoes
        // whatever correlation ids were present so the caller can correlate the failure.
        if (!hasRequiredContext(root)) {
            return ResponseEntity.badRequest().body(
                    nackBody(messageId, transactionId, ErrorCodes.CTX_MISSING_FIELD, ErrorMessages.SCH_MISSING_CONTEXT));
        }

        log.info("event={} sizeBytes={}", LogEvent.PUSH_RECEIVED, rawBytes.length);
        pushService.enqueueForProcessing(rawBody);

        // 200 Ack: the request is accepted for async processing and a catalog/on_publish
        // callback follows. Beckn maps 202 to AckNoCallback (which requires an error and
        // signals that NO callback will follow), so 200 Ack is the correct code here.
        return ResponseEntity.ok(ackBody(messageId, transactionId));
    }

    /**
     * Builds a spec-compliant ACK body:
     * {@code {"message":{"status":"ACK","messageId":"<id>","transactionId":"<id>"}}}
     * Correlation ids that are absent (null/blank) are omitted, never fabricated.
     */
    static Map<String, Object> ackBody(String messageId, String transactionId) {
        Map<String, Object> inner = new HashMap<>();
        inner.put(BecknFields.STATUS, "ACK");
        putIfPresent(inner, BecknFields.MESSAGE_ID, messageId);
        putIfPresent(inner, BecknFields.TRANSACTION_ID, transactionId);
        Map<String, Object> outer = new HashMap<>();
        outer.put(BecknFields.MESSAGE, inner);
        return outer;
    }

    /**
     * Builds a spec-compliant NACK body:
     * {@code {"message":{"status":"NACK","messageId":"<id>","transactionId":"<id>","error":{"code":"...","message":"..."}}}}
     * Correlation ids that are absent (null/blank) are omitted, never fabricated.
     */
    static Map<String, Object> nackBody(String messageId, String transactionId, String code, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put(BecknFields.CODE, code);
        error.put(BecknFields.MESSAGE, message);

        Map<String, Object> inner = new HashMap<>();
        inner.put(BecknFields.STATUS, "NACK");
        putIfPresent(inner, BecknFields.MESSAGE_ID, messageId);
        putIfPresent(inner, BecknFields.TRANSACTION_ID, transactionId);
        inner.put(BecknFields.ERROR, error);

        Map<String, Object> outer = new HashMap<>();
        outer.put(BecknFields.MESSAGE, inner);
        return outer;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    /** Parses the body to a tree, or returns {@code null} on invalid JSON. */
    private JsonNode tryParse(String rawBody) {
        try {
            return objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.warn("event={} reason=invalid-json error={}", LogEvent.PUSH_REJECTED, ErrorSanitizer.sanitize(e));
            return null;
        }
    }

    /** Reads {@code context.<field>} as non-blank text from a parsed tree, or {@code null}. */
    private static String contextText(JsonNode root, String field) {
        if (root == null) return null;
        JsonNode ctx = root.path(BecknFields.CONTEXT);
        if (!ctx.isObject()) return null;
        JsonNode v = ctx.path(field);
        return (v.isTextual() && !v.asText().isBlank()) ? v.asText() : null;
    }

    /**
     * Valid context object present with at least one mandatory Beckn correlation field
     * (messageId or transactionId). No enrichment or fallback — callers must send a
     * complete Beckn context.
     */
    private boolean hasRequiredContext(JsonNode root) {
        if (root == null) {
            return false; // invalid JSON already logged by tryParse
        }
        if (!root.path(BecknFields.CONTEXT).isObject()) {
            log.warn("event={} reason=missing-context", LogEvent.PUSH_REJECTED);
            return false;
        }
        if (contextText(root, BecknFields.MESSAGE_ID) == null
                && contextText(root, BecknFields.TRANSACTION_ID) == null) {
            log.warn("event={} reason=missing-correlation-id", LogEvent.PUSH_REJECTED);
            return false;
        }
        return true;
    }
}
