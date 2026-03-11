package org.beckn.catalogpublish.exception;

import java.util.Set;

public class ValidationException extends RuntimeException {
    private final Set<String> errors;

    public ValidationException(String message) {
        super(message);
        this.errors = Set.of(message);
    }
    public ValidationException(Set<String> errors) {
        super(errors != null && !errors.isEmpty() ? String.join("; ", errors) : "Validation failed");
        this.errors = errors != null ? errors : Set.of();
    }
    public Set<String> getErrors() {
        return errors;
    }
}
