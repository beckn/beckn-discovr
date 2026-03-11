package org.beckn.catalogpublish.dto;

import org.beckn.catalogpublish.util.ErrorSanitizer;

import java.util.List;

/**
 * Sealed hierarchy for catalog processing outcome.
 */
public sealed interface ProcessingResult
        permits ProcessingResult.Success, ProcessingResult.Rejected, ProcessingResult.InternalError {

    String catalogId();
    ProcessingStatus status();

    record Success(String catalogId, int itemCount, List<ProcessingError> partialErrors)
            implements ProcessingResult {
        @Override
        public ProcessingStatus status() {
            return partialErrors.isEmpty() ? ProcessingStatus.ACCEPTED : ProcessingStatus.PARTIAL;
        }
    }

    record Rejected(String catalogId, String errorCode, String message)
            implements ProcessingResult {
        @Override
        public ProcessingStatus status() {
            return ProcessingStatus.REJECTED;
        }
    }

    record InternalError(String catalogId, String sanitizedMessage)
            implements ProcessingResult {
        @Override
        public ProcessingStatus status() {
            return ProcessingStatus.INTERNAL_ERROR;
        }
    }

    static ProcessingResult success(String catalogId, CatalogBatch batch) {
        return new Success(catalogId, batch.savedItems().size(), batch.errors());
    }

    static ProcessingResult rejected(String catalogId, Throwable e) {
        return new Rejected(catalogId, deriveErrorCode(e), ErrorSanitizer.sanitize(e));
    }

    static ProcessingResult internalError(String catalogId, Throwable e) {
        return new InternalError(catalogId, ErrorSanitizer.sanitize(e));
    }

    private static String deriveErrorCode(Throwable e) {
        if (e == null) return "UNKNOWN";
        String name = e.getClass().getSimpleName();
        return name.length() > 32 ? name.substring(0, 32) : name;
    }
}
