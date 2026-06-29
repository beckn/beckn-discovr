package org.beckn.discover.exception;

/**
 * Thrown when the JSON Schema used for request validation has not been
 * initialized yet (typically during application startup).
 *
 * <p>{@link GlobalExceptionHandler} maps this to a 500 Internal Server Error
 * NACK response with {@code NET_DOWNSTREAM_UNAVAILABLE} error code (the Beckn
 * {@code /discover} endpoint does not declare a 503 response).</p>
 */
public class SchemaNotInitializedException extends RuntimeException {

    public SchemaNotInitializedException(String message) {
        super(message);
    }
}
