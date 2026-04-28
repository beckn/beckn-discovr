package org.beckn.catalogpublish.service.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAI-compatible embedding client.
 *
 * <p>Works with any provider that implements the {@code POST /v1/embeddings} API:
 * local Ollama, OpenAI, Azure OpenAI, LocalAI, LM Studio, etc.</p>
 *
 * <p>Active only when {@code app.catalog.text-search.embedding-model.enabled=true}.
 * Retries up to {@code retries} times with {@code retryDelayMs} backoff before
 * giving up. On final failure logs ERROR and returns {@code Optional.empty()} —
 * the item is indexed without a vector so catalog data is not lost.</p>
 *
 * <p>IMPORTANT: The model configured here MUST match {@code DISCOVERY_TEXT_SEARCH_EMBEDDING_NAME}
 * in catalog-discover-job. Changing the model requires recreating the ES index.</p>
 */
@Component
@ConditionalOnProperty(name = "app.catalog.text-search.embedding-model.enabled", havingValue = "true")
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final HttpClient   httpClient;
    private final ObjectMapper objectMapper;
    private final String       embedUrl;
    private final String       model;
    private final String       apiKey;
    private final Duration     timeout;

    public EmbeddingClient(ObjectMapper objectMapper, AppProperties props) {
        this.objectMapper = objectMapper;
        AppProperties.EmbeddingModel emb = props.catalog().textSearch().embeddingModel();
        this.embedUrl     = emb.baseUrl() + "/v1/embeddings";
        this.model        = emb.name();
        this.apiKey       = emb.apiKey();
        this.timeout      = Duration.ofMillis(emb.timeoutMs());
        this.httpClient   = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        log.info("event={} url={} model={}", LogEvent.EMBEDDING_CLIENT_INIT, this.embedUrl, this.model);
    }

    /**
     * Generates an embedding vector for the given text using OpenAI-compatible API.
     * Retries on transient failures. Returns {@code Optional.empty()} only after
     * all attempts are exhausted — the item will be indexed without a vector.
     *
     * @param text text to embed (typically {@code full_text_blob})
     * @return embedding as {@code List<Float>}, or empty if all attempts fail
     */
    @Retryable(
        retryFor = RuntimeException.class,
        maxAttemptsExpression = "${app.catalog.text-search.embedding-model.retries:3}",
        backoff = @Backoff(
            delayExpression = "${app.catalog.text-search.embedding-model.retry-delay-ms:1000}",
            multiplier = 2),
        recover = "embedRecover"
    )
    public Optional<List<Float>> embed(String text) {
        try {
            return doEmbed(text);
        } catch (Exception e) {
            log.warn("event={} model={} error={}", LogEvent.EMBEDDING_ATTEMPT_FAILED, model, e.getMessage());
            throw new RuntimeException("Embedding provider unavailable: " + e.getMessage(), e);
        }
    }

    @org.springframework.retry.annotation.Recover
    public Optional<List<Float>> embedRecover(RuntimeException e, String text) {
        log.error("event={} model={} error={} — item will be indexed without vector",
                LogEvent.EMBEDDING_FAILED, model, e.getMessage(), e);
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Optional<List<Float>> doEmbed(String text) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("model", model, "input", text));
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(embedUrl))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null && !apiKey.isBlank())
            builder.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Embedding provider returned HTTP " + response.statusCode()
                    + " body=" + response.body());
        }

        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
        if (data == null || data.isEmpty()) {
            log.warn("event={} model={} reason=empty-data-array", LogEvent.EMBEDDING_EMPTY, model);
            return Optional.empty();
        }
        List<Double> embedding = (List<Double>) data.get(0).get("embedding");
        if (embedding == null || embedding.isEmpty()) {
            log.warn("event={} model={} reason=empty-embedding-vector", LogEvent.EMBEDDING_EMPTY, model);
            return Optional.empty();
        }
        return Optional.of(embedding.stream().map(Double::floatValue).toList());
    }
}
