package org.beckn.discover.model;

import org.beckn.discover.config.DiscoveryProperties;
import org.springframework.stereotype.Component;

/**
 * Single decision point for the synchronous ACK/NACK response shape.
 *
 * <p>Reads {@code discovery.legacy-ack-nack-support} (default {@code false}) once at construction
 * and, for every ACK/NACK, hands back the matching body type — so the shape switch lives in
 * exactly one place instead of being scattered across the controller and exception handler:</p>
 *
 * <ul>
 *   <li>{@code false} → v2.0 nested {@link AckResponse}
 *       ({@code {"message":{"status":"ACK","messageId":"<uuid>"}}}).</li>
 *   <li>{@code true}  → pre-2.0 flat {@link LegacyAckResponse}
 *       ({@code {"status":"ACK"}}). The legacy shape has no {@code message} wrapper and no
 *       {@code messageId}, so the supplied {@code messageId} is intentionally dropped.</li>
 * </ul>
 */
@Component
public class AckResponseFactory {

    private final boolean legacy;

    public AckResponseFactory(DiscoveryProperties properties) {
        this.legacy = properties.isLegacyAckNackSupport();
    }

    /**
     * Builds an ACK body. In legacy mode the {@code messageId} is dropped (the flat shape
     * never carried one — see commit 03066d7).
     */
    public AckResponseBody ack(String messageId) {
        return legacy ? LegacyAckResponse.ack() : AckResponse.ack(messageId);
    }

    /**
     * Builds a NACK body. The error code VALUE and message VALUE are identical across both
     * shapes; only the envelope and the error field names differ (legacy:
     * {@code errorCode}/{@code errorMessage}; v2.0: {@code code}/{@code message}). In legacy
     * mode the {@code messageId} is dropped.
     */
    public AckResponseBody nack(String messageId, String code, String message) {
        return legacy ? LegacyAckResponse.nack(code, message) : AckResponse.nack(messageId, code, message);
    }
}
