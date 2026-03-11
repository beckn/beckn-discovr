package org.beckn.catalogpublish.exception;

public class FieldExtractionException extends RuntimeException {
    public FieldExtractionException(String message) {
        super(message);
    }
    public FieldExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
