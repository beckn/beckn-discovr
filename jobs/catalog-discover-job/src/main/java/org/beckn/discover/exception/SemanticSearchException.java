package org.beckn.discover.exception;

/**
 * Thrown when semantic search fails — either the intent parser (LLM) call fails,
 * times out, or returns a response that cannot be parsed as valid JSON.
 *
 * <p>This is an unrecoverable error: callers must NOT fall back to keyword search.
 * {@link GlobalExceptionHandler} maps this to a 503 NACK response.</p>
 */
public class SemanticSearchException extends RuntimeException {

    public SemanticSearchException(String message) {
        super(message);
    }

    public SemanticSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
