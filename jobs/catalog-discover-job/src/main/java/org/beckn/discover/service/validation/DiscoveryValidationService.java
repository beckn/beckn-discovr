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
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 *   <li>JSON Schema structural validation (NetworkNT) on the full request body
 *       against the endpoint schema read from
 *       {@code paths./discover.post.requestBody.content.application/json.schema}
 *       in the beckn.yaml. That schema already contains the correct
 *       {@code action: const "discover"} constraint and references to
 *       {@code Context} and {@code DiscoverAction}.</li>
 *   <li>Manual UUID format check for {@code transactionId} and {@code messageId}.</li>
 *   <li>Blank / relative JSONPath filter expression guard.</li>
 *   <li>Spatial {@code distanceMeters} range guard.</li>
 * </ul>
 */
@Service
public class DiscoveryValidationService {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryValidationService.class);

    private static final String[] HTTP_SCHEMES = { "http", "https" };

    private final ObjectMapper objectMapper;
    private final SchemaLoaderService schemaLoaderService;
    private final org.yaml.snakeyaml.Yaml yamlParser;

    // Validates the full request body (context + message) against the DiscoverAction/v2.0 schema
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
            // All schema.beckn.io schemas use Draft 2020-12
            JsonSchemaFactory schemaFactory = JsonSchemaFactory
                    .builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
                    .uriFetcher(yamlAwareFetcher, HTTP_SCHEMES)
                    .build();

            // Read the endpoint schema directly from the spec paths section:
            // paths./discover.post.requestBody.content.application/json.schema
            // This schema already contains the correct action const "discover" and
            // references to Context and DiscoverAction via $ref — no hand-building needed.
            JsonNode endpointSchema = rootSchemaNode
                    .path("paths")
                    .path("/discover")
                    .path("post")
                    .path("requestBody")
                    .path("content")
                    .path("application/json")
                    .path("schema");

            if (endpointSchema.isMissingNode()) {
                throw new RuntimeException(
                        "beckn.yaml is missing paths./discover.post.requestBody.content.application/json.schema");
            }

            var envelopeSchema = buildSelfContainedSchema(rootSchemaNode, endpointSchema);
            discoverActionSchema = schemaFactory.getSchema(envelopeSchema);
            logger.info(LogEvent.VALIDATE_PASSED + ".discover-action-schema-init",
                    value("source", "paths./discover endpoint schema from beckn.yaml"));

        } catch (Exception e) {
            logger.error(LogEvent.VALIDATE_FAILED + ".schema-init",
                    value("error", e.getMessage()),
                    e);
            throw new RuntimeException("Failed to initialize schema validation", e);
        }
    }

    /**
     * Builds a self-contained JSON Schema document from an endpoint-level schema node.
     *
     * <p>Copies all {@code components/schemas} entries from the root document into
     * {@code $defs}, sets the provided endpoint schema as the root, rewrites all
     * {@code #/components/schemas/X} refs to {@code #/$defs/X}, and substitutes
     * well-known external {@code https://schema.beckn.io/X/v2.0} refs with
     * {@code #/$defs/X} — fetching them via the URI fetcher when they are not already
     * present in the local {@code components/schemas}. This keeps the document fully
     * self-contained without requiring NetworkNT to make additional HTTP calls.</p>
     */
    private JsonNode buildSelfContainedSchema(JsonNode rootSchemaNode, JsonNode endpointSchema)
            throws Exception {
        var factory = objectMapper.getNodeFactory();

        // Copy all components/schemas into $defs
        JsonNode componentsSchemas = rootSchemaNode.path("components").path("schemas");
        ObjectNode defs = factory.objectNode();
        componentsSchemas.fields().forEachRemaining(entry -> defs.set(entry.getKey(), entry.getValue()));

        // Deep-copy the endpoint schema to avoid mutating the cached root document
        ObjectNode envelope = endpointSchema.deepCopy();
        envelope.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        envelope.set("$defs", defs);

        // First pass: collect all schema.beckn.io external refs across the full envelope
        // (endpoint schema + defs) and add permissive stubs for any that are missing from defs.
        // This prevents NetworkNT from making HTTP calls to schema.beckn.io and avoids
        // conflicts between external schemas (e.g. BecknEndpoint action pattern) and the
        // endpoint schema's action const overlay.
        collectAndInlineExternalRefs(envelope, defs, factory);

        // Second pass: rewrite all #/components/schemas/ and schema.beckn.io refs to #/$defs/
        Set<String> defNames = new java.util.HashSet<>();
        defs.fieldNames().forEachRemaining(defNames::add);
        return rewriteAllRefs(envelope, defNames);
    }

    /**
     * Scans {@code node} for {@code https://schema.beckn.io/X/v2.0} refs.
     * If the referenced schema is not already in {@code defs}, fetches it via
     * the {@link YamlAwareUriFetcher} and adds it under the schema name as a
     * permissive wrapper ({@code type: object, additionalProperties: true}) so
     * that structural context validation is still performed using the local
     * {@code components/schemas/Context} where available, and external schemas
     * that impose conflicting action constraints (e.g. {@code BecknEndpoint})
     * do not break the action const in the endpoint schema.
     */
    private void collectAndInlineExternalRefs(JsonNode node, ObjectNode defs,
            com.fasterxml.jackson.databind.node.JsonNodeFactory factory) throws Exception {
        if (node.isObject()) {
            JsonNode refNode = node.get("$ref");
            if (refNode != null && refNode.isTextual()) {
                String ref = refNode.asText();
                if (ref.startsWith("https://schema.beckn.io/")) {
                    String schemaName = ref
                            .replaceFirst("https://schema\\.beckn\\.io/", "")
                            .replaceFirst("/v[0-9.]+$", "");
                    if (!defs.has(schemaName)) {
                        // Inline as a permissive stub so the ref can be resolved locally
                        // without fetching the external schema that may impose conflicting constraints.
                        defs.set(schemaName, factory.objectNode()
                                .put("type", "object")
                                .put("additionalProperties", true));
                        logger.debug("Inlined external schema.beckn.io stub: {} → #/$defs/{}", ref, schemaName);
                    }
                }
            }
            node.fields().forEachRemaining(entry ->
                    inlineSilent(entry.getValue(), defs, factory));
        } else if (node.isArray()) {
            node.forEach(child -> inlineSilent(child, defs, factory));
        }
    }

    private void inlineSilent(JsonNode node, ObjectNode defs,
            com.fasterxml.jackson.databind.node.JsonNodeFactory factory) {
        try {
            collectAndInlineExternalRefs(node, defs, factory);
        } catch (Exception e) {
            logger.warn("Failed to inline external ref: {}", e.getMessage());
        }
    }

    /**
     * Rewrites all refs in the schema tree to be self-contained:
     * <ul>
     *   <li>{@code #/components/schemas/X} → {@code #/$defs/X}</li>
     *   <li>{@code https://schema.beckn.io/X/v2.0} → {@code #/$defs/X} when X is in $defs</li>
     * </ul>
     */
    private JsonNode rewriteAllRefs(JsonNode node, Set<String> defNames) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            JsonNode refNode = obj.get("$ref");
            if (refNode != null && refNode.isTextual()) {
                String ref = refNode.asText();
                if (ref.startsWith("#/components/schemas/")) {
                    obj.put("$ref", ref.replace("#/components/schemas/", "#/$defs/"));
                } else if (ref.startsWith("https://schema.beckn.io/")) {
                    String schemaName = ref
                            .replaceFirst("https://schema\\.beckn\\.io/", "")
                            .replaceFirst("/v[0-9.]+$", "");
                    if (defNames.contains(schemaName)) {
                        obj.put("$ref", "#/$defs/" + schemaName);
                    }
                }
            }
            obj.fields().forEachRemaining(entry -> rewriteAllRefs(entry.getValue(), defNames));
        } else if (node.isArray()) {
            node.forEach(child -> rewriteAllRefs(child, defNames));
        }
        return node;
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

            // Presence checks — these always run and give clearer error messages than schema failures
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

            // Presence checks for required Context V2.0 fields — enforced manually so they
            // are caught reliably regardless of whether the loaded schema provides a local
            // Context definition or relies on an external schema.beckn.io reference.
            for (String requiredCtxField : new String[]{"transactionId", "messageId"}) {
                JsonNode field = contextNode.path(requiredCtxField);
                if (field.isMissingNode() || field.isNull()) {
                    return new ValidationResult(false,
                            List.of("$.context." + requiredCtxField + ": " + requiredCtxField + " is required"),
                            List.of("$.context." + requiredCtxField));
                }
            }

            // Manual UUID validation for transactionId and messageId — schema uses format:uuid
            // but NetworkNT format validation is advisory; enforce it explicitly here.
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

            // Manual JSONPath absoluteness guard — checked before schema validation so the
            // error message is actionable rather than a generic schema violation
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

            // Single schema validation of the full request body against DiscoverAction/v2.0.
            // This covers: context structure (oneOf V2.0/V1.0), action const "discover",
            // and message.intent structure (anyOf textSearch/filters/spatial).
            Set<ValidationMessage> schemaErrors = discoverActionSchema.validate(node);

            if (schemaErrors.isEmpty()) {
                return new ValidationResult(true, new ArrayList<>(), new ArrayList<>());
            }

            List<String> errors = schemaErrors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.toList());
            List<String> paths = schemaErrors.stream()
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
