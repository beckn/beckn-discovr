package org.beckn.discover.service.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.exception.SemanticSearchException;
import org.beckn.discover.logging.LogEvent;
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
 * OpenAI-compatible embedding client for query-time vector generation.
 *
 * <p>Works with any provider that implements {@code POST /v1/embeddings}:
 * Ollama (local), OpenAI, Azure OpenAI, Groq, Together AI, etc.
 * Configure via {@code discovery.text-search.embedding-model.*} properties.</p>
 *
 * <p>Active only when {@code discovery.text-search.engine=els-semantic-search}.</p>
 * <ul>
 *   <li>Network/HTTP failures and unparseable responses throw {@link SemanticSearchException}.</li>
 *   <li>A valid response with an empty embedding (model could not embed the text) returns
 *       {@code Optional.empty()} — callers should return an empty result set, not an error.</li>
 * </ul>
 *
 * <p>IMPORTANT: The model name here MUST match the one in catalog-publish-job.
 * Changing the model requires recreating the Elasticsearch index.</p>
 */
@Component
@ConditionalOnProperty(name = "discovery.text-search.engine", havingValue = "els-semantic-search")
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final HttpClient   httpClient;
    private final ObjectMapper objectMapper;
    private final String       embedUrl;
    private final String       model;
    private final String       apiKey;
    private final Duration     timeout;

    public EmbeddingClient(ObjectMapper objectMapper, DiscoveryProperties props) {
        this.objectMapper = objectMapper;
        DiscoveryProperties.TextSearch.EmbeddingModel cfg = props.getTextSearch().getEmbeddingModel();
        this.embedUrl     = cfg.getBaseUrl() + "/v1/embeddings";
        this.model        = cfg.getName();
        this.apiKey       = cfg.getApiKey();
        this.timeout      = Duration.ofMillis(cfg.getTimeoutMs());
        this.httpClient   = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        log.info("event={} url={} model={}", LogEvent.EMBEDDING_CLIENT_INIT, this.embedUrl, this.model);
    }

    /**
     * Generates an embedding vector for the given query text.
     *
     * @param text query text to embed
     * @return embedding as {@code List<Float>}, or {@code Optional.empty()} if the model returned no vector
     * @throws SemanticSearchException if the provider is unreachable, returns a non-200 status, or the response cannot be parsed
     */
    @Retryable(
        retryFor = RuntimeException.class,
        noRetryFor = SemanticSearchException.class,
        maxAttemptsExpression = "${discovery.text-search.embedding-model.retries:3}",
        backoff = @Backoff(
            delayExpression = "${discovery.text-search.embedding-model.retry-delay-ms:1000}",
            multiplier = 2),
        recover = "embedRecover"
    )
    public Optional<List<Float>> embed(String text) {
        try {
            return doEmbed(text);
        } catch (SemanticSearchException e) {
            throw e;
        } catch (Exception e) {
            log.warn("event={} model={} error={}", LogEvent.EMBEDDING_ATTEMPT_FAILED, model, e.getMessage(), e);
            throw new RuntimeException("Embedding provider unavailable: " + e.getMessage(), e);
        }
    }

    @org.springframework.retry.annotation.Recover
    public Optional<List<Float>> embedRecover(RuntimeException e, String text) {
        log.error("event={} model={} error={}", LogEvent.EMBEDDING_FAILED, model, e.getMessage(), e);
        throw new SemanticSearchException("Embedding provider unavailable after retries", e);
    }

    @SuppressWarnings("unchecked")
    private Optional<List<Float>> doEmbed(String text) throws Exception {
        HttpResponse<String> response;
        try {
            String body = objectMapper.writeValueAsString(Map.of("model", model, "input", text));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(embedUrl))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (apiKey != null && !apiKey.isBlank())
                builder.header("Authorization", "Bearer " + apiKey);
            // sendAsync releases the calling thread during I/O; join() surfaces exceptions
            response = httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .join();
        } catch (Exception e) {
            throw new RuntimeException("Embedding provider unavailable or timed out: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            throw new RuntimeException("Embedding provider returned HTTP " + response.statusCode()
                    + " body=" + response.body());
        }

        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
        if (data == null || data.isEmpty()) {
            log.warn("event={} model={} reason=empty-data-array", LogEvent.EMBEDDING_EMPTY, model);
            // Throw SemanticSearchException to stop retrying — empty response won't improve
            throw new SemanticSearchException("Embedding provider returned empty data array");
        }
        List<Double> embedding = (List<Double>) data.get(0).get("embedding");
        if (embedding == null || embedding.isEmpty()) {
            log.warn("event={} model={} reason=empty-embedding-vector", LogEvent.EMBEDDING_EMPTY, model);
            throw new SemanticSearchException("Embedding provider returned empty vector");
        }
        return Optional.of(embedding.stream().map(Double::floatValue).toList());
    }
}
