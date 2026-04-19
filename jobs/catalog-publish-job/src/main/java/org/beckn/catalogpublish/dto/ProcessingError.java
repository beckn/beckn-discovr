package org.beckn.catalogpublish.dto;

/**
 * Per-resource processing error (resourceId, code, sanitized message).
 */
public record ProcessingError(@com.fasterxml.jackson.annotation.JsonAlias("itemId") String resourceId, ProcessingErrorCode errorCode, String message) {}
