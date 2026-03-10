package org.beckn.catalogpublish.exception;

public class CatalogPublishException extends RuntimeException {
    public CatalogPublishException(String message) {
        super(message);
    }
    public CatalogPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
