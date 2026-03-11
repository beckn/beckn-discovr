package org.beckn.catalogpublish.dto;

/**
 * Outcome status for a single catalog processing result.
 */
public enum ProcessingStatus {
    ACCEPTED,       // all items saved, no errors
    PARTIAL,        // some items saved, some errors
    REJECTED,       // permanent business failure
    INTERNAL_ERROR  // transient failure — retriable
}
