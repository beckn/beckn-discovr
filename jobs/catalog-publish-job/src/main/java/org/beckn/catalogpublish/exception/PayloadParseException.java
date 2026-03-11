package org.beckn.catalogpublish.exception;

public class PayloadParseException extends RuntimeException {
    public PayloadParseException(String message) {
        super(message);
    }
    public PayloadParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
