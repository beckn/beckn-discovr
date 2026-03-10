package org.beckn.discover.service.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.beckn.discover.model.DiscoverRequest;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.uri.URIFetcher;

import jakarta.annotation.PostConstruct;

/**
 * Discovery Validation Service (NetworkNT).
 *
 * <p>Uses a remote OpenAPI schema loaded via {@link SchemaLoaderService}.
 * Validation includes:</p>
 * <ul>
 *   <li>JSON Schema structural validation (NetworkNT).</li>
 *   <li>Blank / relative JSONPath filter expression guard.</li>
 * </ul>
 */
@Service
public class DiscoveryValidationService {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryValidationService.class);

    private static final String DISCOVER_REQUEST_SCHEMA_REF = "#/components/schemas/DiscoverRequest";
    private static final String[] HTTP_SCHEMES = { "http", "https" };

    private final ObjectMapper objectMapper;
    private final SchemaLoaderService schemaLoaderService;
    private final org.yaml.snakeyaml.Yaml yamlParser;

    private JsonSchema discoverRequestSchema;

    public DiscoveryValidationService(
            ObjectMapper objectMapper,
            SchemaLoaderService schemaLoaderService,
            org.yaml.snakeyaml.Yaml yamlParser) {
        this.objectMapper = objectMapper;
        this.schemaLoaderService = schemaLoaderService;
        this.yamlParser = yamlParser;
    }

    @PostConstruct
    public void init() {
        try {
            JSONObject rootSchema = schemaLoaderService.getApiSchema();

            URIFetcher yamlAwareFetcher = new YamlAwareUriFetcher(objectMapper, yamlParser);
            JsonSchemaFactory schemaFactory = JsonSchemaFactory
                    .builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7))
                    .uriFetcher(yamlAwareFetcher, HTTP_SCHEMES)
                    .build();

            JsonNode rootSchemaNode = objectMapper.readTree(rootSchema.toString());
            java.net.URI discoverRequestUri = new java.net.URI(DISCOVER_REQUEST_SCHEMA_REF);
            discoverRequestSchema = schemaFactory.getSchema(discoverRequestUri, rootSchemaNode);

        } catch (Exception e) {
            logger.error("Failed to initialize validation", e);
            throw new RuntimeException("Failed to initialize schema validation", e);
        }
    }

    public ValidationResult validateDiscoverRequest(JsonNode node) {
        if (node == null || node.isNull()) {
            logger.warn("Validation failed: Request is null");
            return new ValidationResult(false, List.of("Request cannot be null"), List.of("root"));
        }

        try {
            if (discoverRequestSchema == null) {
                logger.error("Validation schema is not initialized");
                return new ValidationResult(false, List.of("Validation schema not initialized"), List.of("root"));
            }

            Set<ValidationMessage> validationMessages = discoverRequestSchema.validate(node);

            // Additional checks on the filter expression
            JsonNode messageNode = node.get("message");
            if (messageNode != null) {
                JsonNode filtersNode = messageNode.get("filters");
                if (filtersNode != null) {
                    JsonNode expressionNode = filtersNode.get("expression");
                    if (expressionNode != null && expressionNode.isTextual()) {
                        String expression = expressionNode.asText();

                        if (expression.isBlank()) {
                            return new ValidationResult(false,
                                List.of("$.message.filters.expression: filters expression cannot be blank"),
                                List.of("$.message.filters.expression"));
                        }
                        if (!expression.trim().startsWith("$")) {
                            return new ValidationResult(false,
                                List.of("$.message.filters.expression: filters expression must be an absolute JSONPath (e.g. $.catalogs[*]...)"),
                                List.of("$.message.filters.expression"));
                        }
                    }
                }
            }

            if (validationMessages.isEmpty()) {
                return new ValidationResult(true, new ArrayList<>(), new ArrayList<>());
            }

            List<String> errors = validationMessages.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.toList());
            List<String> paths = validationMessages.stream()
                    .map(vm -> vm.getPath() != null ? vm.getPath() : "root")
                    .distinct()
                    .collect(Collectors.toList());

            logger.error("Schema validation FAILED: {} (paths: {})",
                    String.join("; ", errors), String.join(", ", paths));
            return new ValidationResult(false, errors, paths);

        } catch (Exception e) {
            logger.error("Unexpected error during validation: {}", e.getMessage(), e);
            return new ValidationResult(false, List.of("Validation error: " + e.getMessage()), List.of("root"));
        }
    }

    public ValidationResult validateDiscoverRequest(DiscoverRequest request) {
        if (request == null)
            return new ValidationResult(false, List.of("Request cannot be null"), List.of("root"));
        JsonNode node = objectMapper.valueToTree(request);
        return validateDiscoverRequest(node);
    }

    public static class ValidationResult {
        private final boolean isValid;
        private final List<String> errors;
        private final List<String> paths;

        public ValidationResult(boolean isValid, List<String> errors, List<String> paths) {
            this.isValid = isValid;
            this.errors = errors;
            this.paths = paths;
        }

        public boolean isValid()        { return isValid; }
        public List<String> getErrors() { return errors;  }
        public List<String> getPaths()  { return paths;   }
    }
}
