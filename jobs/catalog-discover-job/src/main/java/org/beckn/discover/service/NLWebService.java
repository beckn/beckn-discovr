package org.beckn.discover.service;

import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.model.NLWebRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpServerErrorException;

import java.time.Duration;

/**
 * NLWeb Service
 *
 * <p>Integrates with the NLWeb natural language querying engine using
 * {@link RestClient} (synchronous, non-reactive).</p>
 *
 * <p>{@code @Retryable} retries on I/O errors and 5xx responses.
 * Callers (e.g. {@code DiscoveryService.pathD}) must submit this call to a
 * bounded I/O executor ({@code discoveryQueryExecutor}) so servlet threads
 * are not blocked during the HTTP round-trip.</p>
 */
@Service
public class NLWebService {

    private static final Logger logger = LoggerFactory.getLogger(NLWebService.class);
    private static final int MAX_RETRIES = 3;

    private final DiscoveryProperties discoveryProperties;
    private final RestClient nlWebClient;

    public NLWebService(DiscoveryProperties discoveryProperties, RestClient.Builder restClientBuilder) {
        this.discoveryProperties = discoveryProperties;

        String baseUrl = discoveryProperties.getNlweb().getBaseUrl();
        int timeoutSeconds = discoveryProperties.getNlweb().getTimeoutSeconds();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(timeoutSeconds > 0 ? Duration.ofSeconds(timeoutSeconds) : Duration.ofSeconds(30));

        this.nlWebClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();

        logger.info("NLWeb RestClient initialised with baseUrl: {}", baseUrl);
    }

    /**
     * Queries NLWeb service with the given text search query.
     *
     * <p>Blocks until the HTTP response is received or the read timeout expires.
     * Must be called from a dedicated I/O thread — see class-level Javadoc.</p>
     *
     * @param textSearch the natural language search query
     * @return NLWeb response as JSON string
     */
    @Retryable(
        value       = { ResourceAccessException.class, HttpServerErrorException.class },
        maxAttempts = MAX_RETRIES,
        backoff     = @Backoff(delay = 1000)
    )
    public String queryNLWeb(String textSearch) {
        if (textSearch == null || textSearch.isBlank()) {
            throw new IllegalArgumentException("Text search query cannot be null or empty");
        }

        logger.debug("Querying NLWeb service with query: {}", textSearch);

        NLWebRequest request = new NLWebRequest(textSearch);
        String askEndpoint = discoveryProperties.getNlweb().getAskEndpoint();
        boolean streaming  = discoveryProperties.getNlweb().isStreaming();

        logger.debug("Making request to NLWeb path: {}, streaming: {}", askEndpoint, streaming);

        String response = nlWebClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path(askEndpoint)
                        .queryParam("streaming", streaming)
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);

        logger.info("Successfully received response from NLWeb service");
        return response;
    }
}
