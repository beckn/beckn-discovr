package org.beckn.catalogpublish.exception;

public class PayloadMergeException extends RuntimeException {
    public PayloadMergeException(String message) {
        super(message);
    }
    public PayloadMergeException(String message, Throwable cause) {
        super(message, cause);
    }
}
