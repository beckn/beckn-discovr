package org.beckn.catalogpublish.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "app.catalog.validation-enabled", havingValue = "true")
public class JsonSchemaCatalogValidator implements CatalogMessageValidator {

    private static final Logger log = LoggerFactory.getLogger(JsonSchemaCatalogValidator.class);

    private final JsonSchema schema;

    public JsonSchemaCatalogValidator(AppProperties props, ObjectMapper objectMapper) throws Exception {
        String specUrl = props.catalog().schemaUrl();
        validateSpecUrl(specUrl);
        log.info("validator.loading schemaUrl={}", specUrl);

        String yaml = fetchYaml(specUrl);

        @SuppressWarnings("unchecked")
        Map<String, Object> spec = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);

        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>)
                ((Map<String, Object>) spec.get("components")).get("schemas");

        @SuppressWarnings("unchecked")
        Map<String, Object> catalogPublishAction = deepCopy(
                (Map<String, Object>) schemas.get("CatalogPublishAction"));

        resolveRefs(catalogPublishAction, schemas);

        String schemaJson = objectMapper.writeValueAsString(catalogPublishAction);
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        this.schema = factory.getSchema(schemaJson);

        log.info("validator.ready schemaComponent=CatalogPublishAction");
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

    // ── Private ───────────────────────────────────────────────────────────────

    private static void validateSpecUrl(String specUrl) {
        if (specUrl == null || specUrl.isBlank()) {
            throw new IllegalArgumentException("Schema URL must use HTTPS: " + specUrl);
        }
        URI uri;
        try {
            uri = URI.create(specUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Schema URL must use HTTPS: " + specUrl, e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Schema URL must use HTTPS: " + specUrl);
        }
    }

    private static String fetchYaml(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Failed to fetch Beckn spec from " + url + " — HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * Recursively walks the schema map. Any node with a "$ref" that starts with
     * "http" is replaced with an empty map {} (permissive — external schemas are
     * not fetched). Local "#/components/schemas/X" refs are inlined from the
     * top-level schemas map. A resolving set tracks in-progress refs to break
     * circular references (A → B → A) by replacing the cycle entry with {}.
     */
    @SuppressWarnings("unchecked")
    private static void resolveRefs(Map<String, Object> node, Map<String, Object> schemas) {
        resolveRefsInternal(node, schemas, new HashSet<>());
    }

    @SuppressWarnings("unchecked")
    private static void resolveRefsInternal(Object node, Map<String, Object> schemas, Set<String> resolving) {
        if (node instanceof Map<?, ?> map) {
            var m = (Map<String, Object>) map;
            if (m.containsKey("$ref")) {
                String ref = String.valueOf(m.get("$ref"));
                if (ref.startsWith("http")) {
                    m.clear();   // external ref → permissive empty schema
                    return;
                }
                if (ref.startsWith("#/components/schemas/")) {
                    String name = ref.substring("#/components/schemas/".length());
                    if (resolving.contains(name)) {
                        // circular reference detected — break the cycle
                        m.clear();
                        return;
                    }
                    Object target = schemas.get(name);
                    if (target instanceof Map<?, ?> targetMap) {
                        m.remove("$ref");
                        m.putAll((Map<String, Object>) targetMap);
                        resolving.add(name);
                        resolveRefsInternal(m, schemas, resolving);
                        resolving.remove(name);
                        return;
                    }
                }
            }
            for (var entry : m.entrySet()) {
                resolveRefsInternal(entry.getValue(), schemas, resolving);
            }
        } else if (node instanceof List<?> list) {
            for (var item : list) {
                resolveRefsInternal(item, schemas, resolving);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        var copy = new LinkedHashMap<String, Object>();
        for (var e : source.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) copy.put(e.getKey(), deepCopy((Map<String, Object>) m));
            else if (v instanceof List<?> l) copy.put(e.getKey(), deepCopyList(l));
            else copy.put(e.getKey(), v);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> deepCopyList(List<?> list) {
        var copy = new ArrayList<Object>();
        for (var item : list) {
            if (item instanceof Map<?, ?> m) copy.add(deepCopy((Map<String, Object>) m));
            else if (item instanceof List<?> l) copy.add(deepCopyList(l));
            else copy.add(item);
        }
        return copy;
    }
}
