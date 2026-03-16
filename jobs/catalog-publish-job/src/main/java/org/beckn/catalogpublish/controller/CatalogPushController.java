package org.beckn.catalogpublish.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.exception.ValidationException;
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
    private final long maxPayloadSize;
    private final boolean signatureVerificationEnabled;

    public CatalogPushController(CatalogPushService pushService, AppProperties props) {
        this.pushService = pushService;
        this.maxPayloadSize = props.catalog().maxPayloadSize();
        this.signatureVerificationEnabled = props.http().signatureVerificationEnabled();
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

        log.info("catalog.push.received sizeBytes={}", rawBytes.length);
        pushService.processAsync(rawBody);

        return ResponseEntity.accepted().body(ACK_RESPONSE);
    }
}
