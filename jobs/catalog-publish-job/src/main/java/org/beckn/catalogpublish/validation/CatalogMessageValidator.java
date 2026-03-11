package org.beckn.catalogpublish.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.beckn.catalogpublish.exception.ValidationException;

/**
 * Strategy contract for catalog message validation (schema or no-op).
 */
public interface CatalogMessageValidator {

    void validate(JsonNode message) throws ValidationException;
}
