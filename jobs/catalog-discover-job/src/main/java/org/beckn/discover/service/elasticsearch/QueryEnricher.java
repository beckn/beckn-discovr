package org.beckn.discover.service.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.exception.SemanticSearchException;
import org.beckn.discover.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Enriches a raw search query using an LLM before embedding, improving semantic
 * search quality by expanding the query with related terms, synonyms and domain
 * vocabulary.
 *
 * <p>Works with any OpenAI-compatible {@code POST /v1/chat/completions} provider:
 * Ollama (local), OpenAI, Groq, Azure OpenAI, etc.
 * Configure via {@code discovery.text-search.llm-model.*}.</p>
 *
 * <p>Active only when {@code discovery.text-search.engine=els-semantic-search}.</p>
 *
 * <p>On HTTP or timeout failure throws {@link SemanticSearchException}.
 * If the provider returns an empty response the raw query is used as-is —
 * search continues without enrichment rather than failing.</p>
 */
@Component
@ConditionalOnExpression("'${discovery.text-search.engine:native-els}' == 'els-semantic-search' && '${discovery.text-search.llm-model.enabled:true}' == 'true'")
public class QueryEnricher {

    private static final Logger log = LoggerFactory.getLogger(QueryEnricher.class);

    private final HttpClient   httpClient;
    private final ObjectMapper objectMapper;
    private final String       chatUrl;
    private final String       model;
    private final String       apiKey;
    private final Duration     timeout;
    private final double       temperature;
    private final String       systemPrompt;

    public QueryEnricher(ObjectMapper objectMapper, DiscoveryProperties props) {
        this.objectMapper = objectMapper;
        DiscoveryProperties.TextSearch.LlmModel cfg = props.getTextSearch().getLlmModel();
        this.chatUrl      = cfg.getBaseUrl() + "/v1/chat/completions";
        this.model        = cfg.getName();
        this.apiKey       = cfg.getApiKey();
        this.timeout      = Duration.ofMillis(cfg.getTimeoutMs());
        this.temperature  = cfg.getTemperature();
        this.systemPrompt = cfg.getSystemPrompt();
        this.httpClient   = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        log.info("event={} url={} model={}", LogEvent.QUERY_ENRICHER_INIT, this.chatUrl, this.model);
    }

    /**
     * Enriches the raw user query with related terms and domain vocabulary.
     *
     * @param rawQuery original query from the discover request
     * @return enriched query text, or the raw query if the LLM returned empty content
     * @throws SemanticSearchException if the provider is unreachable or returns a non-200 status
     */
    public String enrich(String rawQuery) {
        log.debug("event={}", LogEvent.QUERY_ENRICHER_RAW);

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", rawQuery)
                    ),
                    "temperature", temperature
            ));
        } catch (Exception e) {
            throw new SemanticSearchException("Failed to serialize query enricher request", e);
        }

        HttpResponse<String> response;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(chatUrl))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            if (apiKey != null && !apiKey.isBlank())
                builder.header("Authorization", "Bearer " + apiKey);
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.error("event={} model={} error={}", LogEvent.QUERY_ENRICHER_FAILED, model, e.getMessage());
            throw new SemanticSearchException("Query enricher provider unavailable or timed out", e);
        }

        if (response.statusCode() != 200) {
            log.error("event={} status={} body={}", LogEvent.QUERY_ENRICHER_HTTP_ERROR, response.statusCode(), response.body());
            throw new SemanticSearchException("Query enricher provider returned HTTP " + response.statusCode());
        }

        return parseContent(response.body(), rawQuery);
    }

    private String parseContent(String responseBody, String rawQuery) {
        try {
            JsonNode root    = objectMapper.readTree(responseBody);
            String   content = root.path("choices").path(0).path("message").path("content").asText("").strip();

            // Strip markdown code fences if the model wrapped its output
            if (content.startsWith("```"))
                content = content.replaceAll("^```(?:\\w+)?\\s*", "").replaceAll("```\\s*$", "").strip();

            if (content.isBlank()) {
                log.warn("event={} model={} reason=using-raw-query", LogEvent.QUERY_ENRICHER_EMPTY_RESPONSE, model);
                return rawQuery;
            }

            log.info("event={}", LogEvent.QUERY_ENRICHER_ENRICHED);
            return content;
        } catch (Exception e) {
            log.error("event={} error={}", LogEvent.QUERY_ENRICHER_PARSE_FAILED, e.getMessage());
            throw new SemanticSearchException("Failed to parse query enricher response", e);
        }
    }
}
