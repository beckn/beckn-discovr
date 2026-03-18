package org.beckn.discover.service.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.config.DiscoveryProperties;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpServerErrorException;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.StringReader;
import java.time.Duration;
import java.util.Map;

/**
 * Service for loading and caching API schema from a remote URL.
 *
 * <p>Fetches schema from GitHub (YAML), converts to JSON, and caches via the
 * Spring Cache abstraction ({@code @Cacheable("schema")}).  The cache TTL is
 * configured in {@link org.beckn.discover.config.CacheConfig} using
 * {@code discovery.schema.cache-ttl-hours} (default 1 h).</p>
 *
 * <p>{@code @Retryable} retries on I/O errors and 5xx responses before
 * propagating the failure to the caller.  On permanent failure the Spring
 * context fails to start (no schema = no validation = unsafe).</p>
 */
@Service
public class SchemaLoaderService {

    private static final Logger logger = LoggerFactory.getLogger(SchemaLoaderService.class);

    private final ObjectMapper objectMapper;
    private final DiscoveryProperties discoveryProperties;
    private final Yaml yamlParser;
    private final RestClient restClient;

    public SchemaLoaderService(
            ObjectMapper objectMapper,
            DiscoveryProperties discoveryProperties,
            Yaml yamlParser) {
        this.objectMapper = objectMapper;
        this.discoveryProperties = discoveryProperties;
        this.yamlParser = yamlParser;

        int timeoutSeconds = discoveryProperties.getSchema().getFetchTimeoutSeconds();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Returns the full API schema, fetching from the remote URL on a cache miss.
     *
     * <p>The result is cached in the {@code "schema"} cache (Caffeine, TTL = 1 h by default).
     * On I/O error or 5xx response, {@code @Retryable} retries up to 3 times with 1-second
     * back-off before propagating the exception.</p>
     */
    @Cacheable("schema")
    @Retryable(
        value   = { ResourceAccessException.class, HttpServerErrorException.class },
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000)
    )
    public JSONObject getApiSchema() throws Exception {
        String schemaUrl = discoveryProperties.getSchema().getUrl();
        logger.info("Schema cache miss — loading from: {}", schemaUrl);

        String content;
        if (schemaUrl.startsWith("classpath:")) {
            String resourcePath = schemaUrl.substring("classpath:".length());
            ClassPathResource resource = new ClassPathResource(resourcePath);
            try (InputStream is = resource.getInputStream()) {
                content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } else {
            content = restClient.get()
                    .uri(schemaUrl)
                    .retrieve()
                    .body(String.class);
        }

        if (content == null || content.isBlank()) {
            throw new RuntimeException("Schema content is empty from: " + schemaUrl);
        }

        JSONObject schemaJson;
        if (schemaUrl.endsWith(".json") || schemaUrl.contains(".json?")) {
            schemaJson = new JSONObject(new JSONTokener(new StringReader(content)));
        } else {
            @SuppressWarnings("unchecked")
            Map<String, Object> yamlMap = yamlParser.load(content);
            String jsonString = objectMapper.writeValueAsString(yamlMap);
            schemaJson = new JSONObject(new JSONTokener(new StringReader(jsonString)));
        }

        if (!schemaJson.has("components") || !schemaJson.getJSONObject("components").has("schemas")) {
            logger.warn("Schema missing components.schemas structure");
        }

        logger.info("Schema loaded and cached successfully from: {}", schemaUrl);
        return schemaJson;
    }

    /** Evicts the cached schema (e.g. for forced refresh in tests or admin tooling). */
    @CacheEvict(value = "schema", allEntries = true)
    public void clearCache() {
        logger.info("Schema cache evicted");
    }
}
