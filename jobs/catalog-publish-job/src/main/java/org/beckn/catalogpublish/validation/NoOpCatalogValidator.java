package org.beckn.catalogpublish.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.catalog.validation-enabled", havingValue = "false", matchIfMissing = true)
public class NoOpCatalogValidator implements CatalogMessageValidator {

    @Override
    public void validate(JsonNode message) {
        // intentionally empty — validation disabled
    }
}
