package org.beckn.seeker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Service for sending HTTP requests to BAP endpoints
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HttpService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final SignatureService signatureService;

    @Value("${http.client.timeout}")
    private int timeoutMs;

    private static final String ON_DISCOVER_ENDPOINT = "/on_discover";
    private static final String ON_PUBLISH_ENDPOINT = "/catalog/on_publish";

    private static final String ACTION_ON_DISCOVER = "on_discover";
    private static final String ACTION_ON_CATALOG_PUBLISH = "on_catalog_publish";

    /**
     * Sends the callback response to the appropriate endpoint (BAP or BPP).
     * This method is retryable based on configuration.
     *
     * @param eventJson The full response event as a JSON string.
     * @return true if the callback was successful, false otherwise.
     */
    @Retryable(value = {
            RestClientException.class }, maxAttemptsExpression = "${http.retry.max-attempts}", backoff = @Backoff(delayExpression = "${http.retry.initial-delay}", maxDelayExpression = "${http.retry.max-delay}", multiplierExpression = "${http.retry.multiplier}"))
    public boolean sendCallback(String eventJson) {
        try {
            JsonNode rootNode = objectMapper.readTree(eventJson);
            JsonNode context = rootNode.path("context");

            if (context.isMissingNode()) {
                log.error("Invalid event structure: missing context. Event: {}", eventJson);
                throw new IllegalArgumentException("Invalid event structure for BAP notification");
            }

            String targetUrl = null;
            String action = context.path("action").asText();
            JsonNode bapUriNode = context.path("bap_uri");
            JsonNode bppUriNode = context.path("bpp_uri");

            if (ACTION_ON_DISCOVER.equals(action)) {
                if (bapUriNode.isMissingNode() || bapUriNode.asText().isEmpty()) {
                    throw new IllegalArgumentException("Action is " + ACTION_ON_DISCOVER + " but bap_uri is missing");
                }
                targetUrl = normalizeBaseUrl(bapUriNode.asText()) + ON_DISCOVER_ENDPOINT;
            } else if (ACTION_ON_CATALOG_PUBLISH.equals(action)) {
                if (bppUriNode.isMissingNode() || bppUriNode.asText().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Action is " + ACTION_ON_CATALOG_PUBLISH + " but bpp_uri is missing");
                }
                targetUrl = normalizeBaseUrl(bppUriNode.asText()) + ON_PUBLISH_ENDPOINT;
            } else {
                log.error("Unknown or unsupported action: {}", action);
                throw new IllegalArgumentException("Unknown or unsupported action: " + action);
            }

            log.info("Resolved target URL for action {}: {}", action, targetUrl);

            // Normalize JSON to compact format for consistent signature validation
            String requestBody = objectMapper.writeValueAsString(rootNode);
           
            // Prepare headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Add signature if enabled
            if (signatureService.isEnabled()) {
                String authHeader = signatureService.generateAuthHeader(requestBody);
                headers.set("Authorization", authHeader);
            }

            // Create HTTP entity
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            // Make the HTTP POST request
            ResponseEntity<String> response = restTemplate.exchange(
                    targetUrl,
                    HttpMethod.POST,
                    entity,
                    String.class);

            log.info("Successfully sent callback response: {} - Status: {}", targetUrl,
                    response.getStatusCode());
            return true;

        } catch (RestClientException e) {
            log.error("HTTP error sending callback: {}", e.getMessage(), e);
            throw e; // Re-throw to trigger retry
        } catch (Exception e) {
            log.error("Error sending callback response: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to send callback", e);
        }
    }

    /** Strips a trailing slash from a base URL so path constants can always start with '/'. */
    private static String normalizeBaseUrl(String url) {
        return (url != null && url.endsWith("/")) ? url.substring(0, url.length() - 1) : url;
    }

}