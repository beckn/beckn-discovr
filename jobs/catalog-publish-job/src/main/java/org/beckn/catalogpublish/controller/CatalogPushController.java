package org.beckn.catalogpublish.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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

    private final CatalogPushService pushService;
    private final ObjectMapper objectMapper;
    private final long maxPayloadSize;

    public CatalogPushController(CatalogPushService pushService, AppProperties props, ObjectMapper objectMapper) {
        this.pushService = pushService;
        this.maxPayloadSize = props.catalog().maxPayloadSize();
        this.objectMapper = objectMapper;
    }

    @PostMapping("/catalog/push")
    public ResponseEntity<Map<String, Object>> push(
            @RequestBody byte[] rawBytes,
            HttpServletRequest request) {

        if (rawBytes.length > maxPayloadSize) {
            log.warn("event={} sizeBytes={} limit={}", LogEvent.PUSH_REJECTED, rawBytes.length, maxPayloadSize);
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Payload too large");
        }

        String rawBody = new String(rawBytes, StandardCharsets.UTF_8);
        String enrichedBody = enrichContextIfNeeded(rawBody);

        log.info("event={} sizeBytes={}", LogEvent.PUSH_RECEIVED, rawBytes.length);
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

            JsonNode ctxNode = root.get(BecknFields.CONTEXT);
            if (!(ctxNode instanceof ObjectNode)) {
                // Only enrich when a context object is already present.
                return rawBody;
            }
            ObjectNode context = (ObjectNode) ctxNode;

            // Core Beckn context defaults (do not overwrite existing non-blank values)
            if (isBlank(textOrNull(context.get(BecknFields.VERSION)))) {
                context.put(BecknFields.VERSION, "2.0.0");
            }
            if (isBlank(textOrNull(context.get(BecknFields.ACTION)))) {
                context.put(BecknFields.ACTION, "on_discover");
            }
            if (isBlank(textOrNull(context.get(BecknFields.TIMESTAMP)))) {
                context.put(BecknFields.TIMESTAMP, Instant.now().toString());
            }
            if (isBlank(textOrNull(context.get(BecknFields.MESSAGE_ID)))) {
                context.put(BecknFields.MESSAGE_ID, UUID.randomUUID().toString());
            }
            if (isBlank(textOrNull(context.get(BecknFields.TRANSACTION_ID)))) {
                context.put(BecknFields.TRANSACTION_ID, UUID.randomUUID().toString());
            }
            if (isBlank(textOrNull(context.get(BecknFields.BAP_ID)))) {
                context.put(BecknFields.BAP_ID, "dummy-bap-id");
            }
            if (isBlank(textOrNull(context.get(BecknFields.TTL)))) {
                context.put(BecknFields.TTL, "PT30S");
            }

            // BPP context defaults — derive from first catalog if missing in context
            String updatedBppId = textOrNull(context.get(BecknFields.BPP_ID));
            String updatedBppUri = textOrNull(context.get(BecknFields.BPP_URI));

            if (isBlank(updatedBppId) || isBlank(updatedBppUri)) {
                // Try to derive from the first catalog in the payload
                JsonNode catalogs = root.path("message").path(BecknFields.CATALOGS);
                if (catalogs.isArray() && !catalogs.isEmpty()) {
                    JsonNode firstCatalog = catalogs.get(0);
                    if (isBlank(updatedBppId)) {
                        String catalogBppId = textOrNull(firstCatalog.get(BecknFields.BPP_ID));
                        if (!isBlank(catalogBppId)) {
                            context.put(BecknFields.BPP_ID, catalogBppId);
                        } else {
                            log.warn("event={} reason=missing-bppId", LogEvent.PUSH_RECEIVED);
                        }
                    }
                    if (isBlank(updatedBppUri)) {
                        String catalogBppUri = textOrNull(firstCatalog.get(BecknFields.BPP_URI));
                        if (!isBlank(catalogBppUri)) {
                            context.put(BecknFields.BPP_URI, catalogBppUri);
                        } else {
                            log.warn("event={} reason=missing-bppUri", LogEvent.PUSH_RECEIVED);
                        }
                    }
                }
            }

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("event={} reason=context-enrichment-failed error={}", LogEvent.PUSH_REJECTED, e.toString());
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
