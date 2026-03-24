package org.beckn.discover.service.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.model.DiscoverRequest;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static net.logstash.logback.argument.StructuredArguments.value;

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
 *   <li>JSON Schema structural validation (NetworkNT) on the {@code message} field.</li>
 *   <li>Blank / relative JSONPath filter expression guard.</li>
 * </ul>
 *
 * <p>NetworkNT is a JSON Schema validator, not an OpenAPI parser — it cannot
 * automatically navigate OpenAPI {@code paths} to locate the request body schema.
 * Instead, the {@code DiscoverAction} schema {@code $id} is read directly from
 * {@code components/schemas/DiscoverAction} in the loaded beckn.yaml and used to
 * fetch+compile the external schema via {@link YamlAwareUriFetcher}.</p>
 */
@Service
public class DiscoveryValidationService {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryValidationService.class);

    private static final String[] HTTP_SCHEMES = { "http", "https" };

    private final ObjectMapper objectMapper;
    private final SchemaLoaderService schemaLoaderService;
    private final org.yaml.snakeyaml.Yaml yamlParser;

    // Validates request.message against the DiscoverAction schema
    private JsonSchema discoverActionSchema;

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
            JsonNode rootSchemaNode = objectMapper.readTree(rootSchema.toString());

            URIFetcher yamlAwareFetcher = new YamlAwareUriFetcher(objectMapper, yamlParser);
            // DiscoverAction (and all schema.beckn.io schemas) use Draft 2020-12
            JsonSchemaFactory schemaFactory = JsonSchemaFactory
                    .builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
                    .uriFetcher(yamlAwareFetcher, HTTP_SCHEMES)
                    .build();

            // Validate message.intent against Intent/v2.0.
            // Context/v2.0 has additionalProperties:false which would reject system extensions
            // like schemaContext. Scoping validation to message.intent covers the discovery
            // payload structure (textSearch, filters, spatial) without conflicting with
            // system-level context extensions.
            // NetworkNT is not an OpenAPI parser — we read the $id from Intent in components/schemas.
            JsonNode intentSchemaNode = rootSchemaNode.path("components").path("schemas").path("Intent");
            String schemaId = intentSchemaNode.path("$id").asText(null);

            if (schemaId != null && (schemaId.startsWith("http://") || schemaId.startsWith("https://"))) {
                discoverActionSchema = schemaFactory.getSchema(new java.net.URI(schemaId));
                logger.info(LogEvent.VALIDATE_PASSED + ".schema-init",
                        value("schemaId", schemaId));
            } else {
                // Intent not in root schema — use the known external URI directly
                discoverActionSchema = schemaFactory.getSchema(
                        new java.net.URI("https://schema.beckn.io/Intent/v2.0"));
                logger.info(LogEvent.VALIDATE_PASSED + ".schema-init",
                        value("schemaId", "https://schema.beckn.io/Intent/v2.0"));
            }

        } catch (Exception e) {
            logger.error(LogEvent.VALIDATE_FAILED + ".schema-init",
                    value("error", e.getMessage()),
                    e);
            throw new RuntimeException("Failed to initialize schema validation", e);
        }
    }

    public ValidationResult validateDiscoverRequest(JsonNode node) {
        if (node == null || node.isNull()) {
            logger.warn(LogEvent.VALIDATE_FAILED, value("reason", "null-request"));
            return new ValidationResult(false, List.of("Request cannot be null"), List.of("root"));
        }

        try {
            if (discoverActionSchema == null) {
                logger.error(LogEvent.VALIDATE_FAILED, value("reason", "schema-not-initialized"));
                return new ValidationResult(false, List.of("Validation schema not initialized"), List.of("root"));
            }

            // Validate top-level required fields (Context/v2.0 strict schema excluded — see init())
            JsonNode contextNode = node.path("context");
            if (contextNode.isMissingNode() || contextNode.isNull()) {
                return new ValidationResult(false, List.of("$.context: context is required"), List.of("$.context"));
            }
            JsonNode messageNode = node.path("message");
            if (messageNode.isMissingNode() || messageNode.isNull()) {
                return new ValidationResult(false, List.of("$.message: message is required"), List.of("$.message"));
            }
            JsonNode intentNode = messageNode.path("intent");
            if (intentNode.isMissingNode() || intentNode.isNull()) {
                return new ValidationResult(false, List.of("$.message.intent: intent is required"), List.of("$.message.intent"));
            }

            // Manual UUID validation for transactionId and messageId
            var uuidError = validateUuid(contextNode, "transactionId")
                    .or(() -> validateUuid(contextNode, "messageId"));
            if (uuidError.isPresent()) {
                return new ValidationResult(false, List.of(uuidError.get()), List.of("$.context"));
            }

            // Manual spatial constraint validation (distanceMeters minimum:0 from SpatialConstraint/v2.0)
            JsonNode spatialNode = intentNode.path("spatial");
            if (spatialNode.isArray()) {
                for (int i = 0; i < spatialNode.size(); i++) {
                    JsonNode item = spatialNode.get(i);
                    JsonNode dm = item.path("distanceMeters");
                    if (dm.isNumber() && dm.doubleValue() < 0) {
                        return new ValidationResult(false,
                            List.of("$.message.intent.spatial[" + i + "].distanceMeters: must be >= 0 (minimum: 0)"),
                            List.of("$.message.intent.spatial[" + i + "].distanceMeters"));
                    }
                }
            }

            // Validate message.intent against Intent/v2.0 schema
            Set<ValidationMessage> validationMessages = discoverActionSchema.validate(intentNode);
            if (!intentNode.isMissingNode()) {
                JsonNode filtersNode = intentNode.path("filters");
                if (!filtersNode.isMissingNode()) {
                    JsonNode expressionNode = filtersNode.path("expression");
                    if (expressionNode.isTextual()) {
                        String expression = expressionNode.asText();
                        if (expression.isBlank()) {
                            return new ValidationResult(false,
                                List.of("$.message.intent.filters.expression: filters expression cannot be blank"),
                                List.of("$.message.intent.filters.expression"));
                        }
                        if (!expression.trim().startsWith("$")) {
                            return new ValidationResult(false,
                                List.of("$.message.intent.filters.expression: filters expression must be an absolute JSONPath (e.g. $.catalogs[*]...)"),
                                List.of("$.message.intent.filters.expression"));
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

            logger.error(LogEvent.VALIDATE_FAILED,
                    value("errors", errors),
                    value("paths", paths));
            return new ValidationResult(false, errors, paths);

        } catch (Exception e) {
            logger.error(LogEvent.VALIDATE_FAILED,
                    value("reason", "unexpected-error"),
                    value("error", e.getMessage()),
                    e);
            return new ValidationResult(false, List.of("Validation error: " + e.getMessage()), List.of("root"));
        }
    }

    /** Returns an error message if the field is present and not a valid UUID, empty otherwise. */
    private static java.util.Optional<String> validateUuid(JsonNode ctx, String field) {
        JsonNode node = ctx.path(field);
        if (node.isMissingNode() || node.isNull()) return java.util.Optional.empty();
        try {
            UUID.fromString(node.asText());
            return java.util.Optional.empty();
        } catch (IllegalArgumentException e) {
            return java.util.Optional.of("$.context." + field + ": invalid uuid — must be a valid UUID v4 (got: " + node.asText() + ")");
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
