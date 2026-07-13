package org.beckn.discover.model;

/**
 * Marker interface for the synchronous ACK/NACK response body.
 *
 * <p>Implemented by both response shapes so the controller / exception handler can
 * return either without scattering conditionals:</p>
 * <ul>
 *   <li>{@link AckResponse} — Beckn Protocol v2.0 nested shape
 *       ({@code {"message":{"status":...}}}), the default.</li>
 *   <li>{@link LegacyAckResponse} — pre-2.0 flat shape ({@code {"status":...}}),
 *       emitted only when {@code discovery.legacy-ack-nack-support=true}.</li>
 * </ul>
 *
 * <p>Which one is produced is decided in a single place — {@link AckResponseFactory}.</p>
 */
public interface AckResponseBody {
}
