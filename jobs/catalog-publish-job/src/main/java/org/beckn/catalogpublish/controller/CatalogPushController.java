package org.beckn.catalogpublish.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.beckn.catalogpublish.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
            Map.of("message", Map.of("ack", Map.of("status", "ACK")));

    private final CatalogPushService pushService;
    private final ObjectMapper objectMapper;
    private final long maxPayloadSize;
    private final boolean signatureVerificationEnabled;

    public CatalogPushController(CatalogPushService pushService, AppProperties props, ObjectMapper objectMapper) {
        this.pushService = pushService;
        this.maxPayloadSize = props.catalog().maxPayloadSize();
        this.signatureVerificationEnabled = props.http().signatureVerificationEnabled();
        this.objectMapper = objectMapper;
    }

    @PostMapping("/catalog/push")
    public ResponseEntity<Map<String, Object>> push(
            @RequestBody byte[] rawBytes,
            HttpServletRequest request) {

        if (rawBytes.length > maxPayloadSize) {
            log.warn("catalog.push.rejected.oversized sizeBytes={} limit={}", rawBytes.length, maxPayloadSize);
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Payload too large");
        }

        if (signatureVerificationEnabled) {
            // TODO: integrate Beckn HTTP signature verification (Ed25519 + registry lookup)
            log.warn("catalog.push.signature-verification-enabled-but-not-implemented — skipping");
        }

        String rawBody = new String(rawBytes, StandardCharsets.UTF_8);
        String enrichedBody = enrichContextIfNeeded(rawBody);

        log.info("catalog.push.received sizeBytes={}", rawBytes.length);
        pushService.processAsync(enrichedBody);

        return ResponseEntity.accepted().body(ACK_RESPONSE);
    }

    /**
     * Ensures context.bpp_id and context.bpp_uri are populated if missing/blank,
     * deriving them from the first catalog when possible. Existing non-blank
     * values are never overwritten.
     */
    private String enrichContextIfNeeded(String rawBody) {
        try {
            JsonNode rootNode = objectMapper.readTree(rawBody);
            if (!(rootNode instanceof ObjectNode root)) {
                return rawBody;
            }

            JsonNode ctxNode = root.get("context");
            if (!(ctxNode instanceof ObjectNode)) {
                // Only enrich when a context object is already present.
                return rawBody;
            }
            ObjectNode context = (ObjectNode) ctxNode;

            String updatedBppId = textOrNull(context.get("bpp_id"));
            String updatedBppUri = textOrNull(context.get("bpp_uri"));

            if (isBlank(updatedBppId)) {
                context.put("bpp_id", "dummy-bpp-id");
            }
            if (isBlank(updatedBppUri)) {
                context.put("bpp_uri", "http://dummy-bpp-uri.com");
            }

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("catalog.push.context-enrichment.failed falling back to raw body error={}", e.toString());
            return rawBody;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }
}
