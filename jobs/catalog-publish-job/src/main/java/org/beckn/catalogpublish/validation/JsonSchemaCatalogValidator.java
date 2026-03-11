package org.beckn.catalogpublish.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.exception.ValidationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "app.catalog.validation-enabled", havingValue = "true")
public class JsonSchemaCatalogValidator implements CatalogMessageValidator {

    private final JsonSchema schema;

    public JsonSchemaCatalogValidator(AppProperties props, ResourceLoader resources, ObjectMapper objectMapper) throws Exception {
        String path = "classpath:" + props.catalog().schemaFile();
        try (InputStream is = resources.getResource(path).getInputStream()) {
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            schema = factory.getSchema(is);
        }
    }

    @Override
    public void validate(JsonNode message) {
        Set<ValidationMessage> errors = schema.validate(message);
        if (!errors.isEmpty()) {
            Set<String> messages = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.toSet());
            throw new ValidationException(messages);
        }
    }
}
