package org.beckn.catalogpublish.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.catalogpublish.dto.ParsedCatalogMessage;
import org.beckn.catalogpublish.validation.CatalogMessageValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class ValidateStepTest {

    @Test
    void validate_noOpValidatorDoesNothing() {
        CatalogMessageValidator noOp = new org.beckn.catalogpublish.validation.NoOpCatalogValidator();
        ValidateStep step = new ValidateStep(noOp);
        ObjectNode root = new ObjectMapper().createObjectNode();
        root.putObject("context").put("bppId", "b1");
        var parsed = new ParsedCatalogMessage(root, null, java.util.List.of());
        assertThatCode(() -> step.validate(parsed)).doesNotThrowAnyException();
    }
}
