package org.beckn.catalogpublish.dto;

/**
 * Per-item processing error (itemId, code, sanitized message).
 */
public record ProcessingError(String itemId, ProcessingErrorCode errorCode, String message) {}
