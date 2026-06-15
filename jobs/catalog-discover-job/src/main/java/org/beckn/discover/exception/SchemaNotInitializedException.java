package org.beckn.discover.exception;

/**
 * Thrown when the JSON Schema used for request validation has not been
 * initialized yet (typically during application startup).
 *
 * <p>{@link GlobalExceptionHandler} maps this to a 503 Service Unavailable
 * NACK response with {@code NET_DOWNSTREAM_UNAVAILABLE} error code.</p>
 */
public class SchemaNotInitializedException extends RuntimeException {

    public SchemaNotInitializedException(String message) {
        super(message);
    }
}
