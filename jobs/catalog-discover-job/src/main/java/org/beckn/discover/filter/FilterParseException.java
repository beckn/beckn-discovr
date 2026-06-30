package org.beckn.discover.filter;

/**
 * Thrown when a filter expression is not valid RFC 9535 JSONPath. At the request
 * boundary this maps to a {@code SCH_INVALID_JSONPATH} NACK.
 */
public class FilterParseException extends RuntimeException {
    public FilterParseException(String message) {
        super(message);
    }

    public FilterParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
