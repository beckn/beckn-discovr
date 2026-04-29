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

    private static final Map<String, Object> ACK_RESPONSE =
            Map.of("status", "ACK");
    private static final Map<String, Object> NACK_MISSING_CONTEXT = Map.of(
            "status", "NACK",
            "error", Map.of("errorCode", ErrorCodes.SCH_REQUIRED_FIELD_MISSING,
                    "errorMessage", ErrorMessages.SCH_MISSING_CONTEXT));

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
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
                    Map.of("status", "NACK",
                            "error", Map.of("errorCode", ErrorCodes.REQUEST_TOO_LARGE,
                                    "errorMessage", ErrorMessages.REQUEST_TOO_LARGE)));
        }

        String rawBody = new String(rawBytes, StandardCharsets.UTF_8);

        correlationContext.setTagsFromHttp(request.getHeader("X-Tags"));

        // Validate required context fields — reject with NACK if missing
        if (!hasRequiredContext(rawBody)) {
            return ResponseEntity.badRequest().body(NACK_MISSING_CONTEXT);
        }

        log.info("event={} sizeBytes={}", LogEvent.PUSH_RECEIVED, rawBytes.length);
        pushService.enqueueForProcessing(rawBody);

        return ResponseEntity.accepted().body(ACK_RESPONSE);
    }

    /**
     * Validates that the payload has a valid context object with at least one
     * mandatory Beckn correlation field (messageId or transactionId).
     * No enrichment or fallback — callers must send a complete Beckn context.
     */
    private boolean hasRequiredContext(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode ctx = root.path(BecknFields.CONTEXT);
            if (ctx.isMissingNode() || !ctx.isObject()) {
                log.warn("event={} reason=missing-context", LogEvent.PUSH_REJECTED);
                return false;
            }
            boolean hasMessageId = !ctx.path(BecknFields.MESSAGE_ID).isMissingNode()
                    && !ctx.path(BecknFields.MESSAGE_ID).asText("").isBlank();
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
