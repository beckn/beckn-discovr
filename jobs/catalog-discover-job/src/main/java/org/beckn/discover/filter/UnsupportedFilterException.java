package org.beckn.discover.filter;

/**
 * Thrown when an expression is valid RFC 9535 but cannot be expressed on the
 * target engine (the capability gate) — e.g. a slice with a step, or an
 * unmappable function on PostgreSQL. At the request boundary this maps to a NACK
 * with a precise reason rather than a downstream failure with no callback.
 */
public class UnsupportedFilterException extends RuntimeException {
    public UnsupportedFilterException(String message) {
        super(message);
    }
}
