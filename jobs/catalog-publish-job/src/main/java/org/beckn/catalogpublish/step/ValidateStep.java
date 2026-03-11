package org.beckn.catalogpublish.step;

import org.beckn.catalogpublish.dto.ParsedCatalogMessage;
import org.beckn.catalogpublish.validation.CatalogMessageValidator;
import org.springframework.stereotype.Component;

@Component
public class ValidateStep {

    private final CatalogMessageValidator validator;

    public ValidateStep(CatalogMessageValidator validator) {
        this.validator = validator;
    }

    public void validate(ParsedCatalogMessage parsed) {
        validator.validate(parsed.rootNode());
    }
}
