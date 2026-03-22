package org.beckn.seeker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.beckn.auth.BecknAuth;
import org.beckn.seeker.common.BecknFields;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Service for sending HTTP requests to BAP endpoints
 */
@Slf4j
@Service
public class HttpService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final BecknAuth becknAuth;

    @Value("${signing.enabled:false}")
    private boolean signingEnabled;

    @Value("${http.client.timeout}")
    private int timeoutMs;

    public HttpService(RestTemplate restTemplate, ObjectMapper objectMapper, BecknAuth becknAuth) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.becknAuth = becknAuth;
    }

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
            JsonNode context = rootNode.path(BecknFields.CONTEXT);

            if (context.isMissingNode()) {
                log.error("Invalid event structure: missing context. Event: {}", eventJson);
                throw new IllegalArgumentException("Invalid event structure for BAP notification");
            }

            String targetUrl = null;
            String action = context.path(BecknFields.ACTION).asText();
            // Beckn v2.0: context fields use camelCase
            JsonNode bapUriNode = context.path(BecknFields.BAP_URI);
            JsonNode bppUriNode = context.path(BecknFields.BPP_URI);

            if (ACTION_ON_DISCOVER.equals(action)) {
                if (bapUriNode.isMissingNode() || bapUriNode.asText().isEmpty()) {
                    throw new IllegalArgumentException("Action is " + ACTION_ON_DISCOVER + " but bapUri is missing");
                }
                targetUrl = normalizeBaseUrl(bapUriNode.asText()) + ON_DISCOVER_ENDPOINT;
            } else if (ACTION_ON_CATALOG_PUBLISH.equals(action)) {
                if (bppUriNode.isMissingNode() || bppUriNode.asText().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Action is " + ACTION_ON_CATALOG_PUBLISH + " but bppUri is missing");
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
            if (signingEnabled) {
                log.info("dispatcher.signing.start targetUrl={} action={}", targetUrl, action);
                headers.set("Authorization", becknAuth.generateAuthHeader(requestBody));
                log.info("dispatcher.signing.done targetUrl={} action={}", targetUrl, action);
            }

            // Create HTTP entity
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            // Make the HTTP POST request — handle 409 as AckNoCallback (not an error)
            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        targetUrl,
                        HttpMethod.POST,
                        entity,
                        String.class);

                parseAckResponse(response, targetUrl);
                return true;

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.CONFLICT) {
                    // HTTP 409 = AckNoCallback: BAP has no registered callback — log and skip
                    log.info("AckNoCallback (HTTP 409) from {}: BAP has no registered callback, skipping", targetUrl);
                    return true;
                }
                throw e;
            }

        } catch (RestClientException e) {
            log.error("HTTP error sending callback: {}", e.getMessage(), e);
            throw e; // Re-throw to trigger retry
        } catch (Exception e) {
            log.error("Error sending callback response: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to send callback", e);
        }
    }

    /**
     * Parses the Beckn v2.0 ACK/NACK response body.
     * v2.0 format: {@code {"status":"ACK"}} or {@code {"status":"NACK","error":{"errorCode":"...","errorMessage":"..."}}}
     */
    private void parseAckResponse(ResponseEntity<String> response, String targetUrl) {
        var statusCode = response.getStatusCode();
        var body = response.getBody();
        log.info("Callback response from {}: HTTP {}", targetUrl, statusCode);

        if (body == null || body.isBlank()) {
            log.warn("Empty response body from {}", targetUrl);
            return;
        }
        try {
            var responseNode = objectMapper.readTree(body);
            var status = responseNode.path(BecknFields.STATUS).asText();
            if ("NACK".equals(status)) {
                var error = responseNode.path(BecknFields.ERROR);
                var errorCode = error.path(BecknFields.ERROR_CODE).asText();
                var errorMessage = error.path(BecknFields.ERROR_MESSAGE).asText();
                log.warn("NACK received from {}: errorCode={}, errorMessage={}", targetUrl, errorCode, errorMessage);
            } else {
                log.debug("ACK received from {}", targetUrl);
            }
        } catch (Exception e) {
            log.warn("Could not parse ACK response body from {}: {}", targetUrl, e.getMessage());
        }
    }

    /** Strips a trailing slash from a base URL so path constants can always start with '/'. */
    private static String normalizeBaseUrl(String url) {
        return (url != null && url.endsWith("/")) ? url.substring(0, url.length() - 1) : url;
    }

}