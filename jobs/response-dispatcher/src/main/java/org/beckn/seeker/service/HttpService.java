package org.beckn.seeker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.beckn.auth.BecknAuth;
import org.beckn.seeker.common.BecknFields;
import org.beckn.seeker.config.HttpClientProperties;
import org.beckn.seeker.config.SigningProperties;
import org.beckn.seeker.logging.LogEvent;
import org.beckn.seeker.metrics.DispatcherMetrics;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.URI;

import static net.logstash.logback.argument.StructuredArguments.value;

/**
 * Service for sending HTTP requests to BAP endpoints.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HttpService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final BecknAuth becknAuth;
    private final SigningProperties signingProperties;
    private final DispatcherMetrics dispatcherMetrics;
    private final HttpClientProperties httpClientProperties;

    private static final String ON_DISCOVER_ENDPOINT = "/on_discover";
    private static final String ON_PUBLISH_ENDPOINT = "/catalog/on_publish";

    private static final String ACTION_ON_DISCOVER = "on_discover";
    private static final String ACTION_ON_CATALOG_PUBLISH = "catalog/on_publish";

    /**
     * Sends the callback response to the appropriate endpoint (BAP or BPP).
     * This method is retryable based on configuration.
     *
     * @param eventJson The full response event as a JSON string.
     * @return true if the callback was successful, false otherwise.
     */
    @Retryable(
            value = { RestClientException.class },
            maxAttemptsExpression = "${http.retry.max-attempts}",
            backoff = @Backoff(
                    delayExpression = "${http.retry.initial-delay}",
                    maxDelayExpression = "${http.retry.max-delay}",
                    multiplierExpression = "${http.retry.multiplier}"
            )
    )
    public boolean sendCallback(String eventJson) {
        try {
            JsonNode rootNode = objectMapper.readTree(eventJson);
            JsonNode context = rootNode.path(BecknFields.CONTEXT);

            if (context.isMissingNode()) {
                log.error("{}", value("event", LogEvent.CALLBACK_ERROR),
                        value("reason", "missing context"),
                        value("eventJson", truncate(eventJson, 2000)));
                throw new IllegalArgumentException("Invalid event structure for BAP notification");
            }

            String targetUrl = null;
            String action = context.path(BecknFields.ACTION).asText();
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
                log.error("{}", value("event", LogEvent.CALLBACK_ERROR),
                        value("reason", "unknown action"),
                        value("action", action));
                throw new IllegalArgumentException("Unknown or unsupported action: " + action);
            }

            // SSRF guard — validate before any outbound HTTP call
            if (httpClientProperties.urlValidationEnabled()) {
                try {
                    validateCallbackUrl(targetUrl);
                } catch (IllegalArgumentException ssrfEx) {
                    dispatcherMetrics.recordSsrfBlocked();
                    throw ssrfEx;
                }
            }

            log.info("{}", value("event", LogEvent.CALLBACK_RESOLVED),
                    value("action", action),
                    value("targetUrl", targetUrl));

            // Normalize JSON to compact format for consistent signature validation
            String requestBody = objectMapper.writeValueAsString(rootNode);

            // Prepare headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Add signature if enabled
            if (signingProperties.enabled()) {
                log.info("{}", value("event", LogEvent.SIGNATURE_INIT),
                        value("targetUrl", targetUrl),
                        value("action", action));
                headers.set("Authorization", becknAuth.generateAuthHeader(requestBody));
                log.info("{}", value("event", LogEvent.SIGNATURE_GENERATED),
                        value("targetUrl", targetUrl));
            }

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            final String resolvedUrl = targetUrl;
            Timer.Sample sample = Timer.start();

            try {
                log.info("{}", value("event", LogEvent.CALLBACK_SENT),
                        value("targetUrl", resolvedUrl));

                ResponseEntity<String> response = restTemplate.exchange(
                        resolvedUrl, HttpMethod.POST, entity, String.class);

                sample.stop(dispatcherMetrics.callbackTimerSuccess());
                parseAckResponse(response, targetUrl);
                dispatcherMetrics.incrementSuccess();
                return true;

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.CONFLICT) {
                    sample.stop(dispatcherMetrics.callbackTimerSuccess());
                    log.info("{}", value("event", LogEvent.CALLBACK_ACK_NO_CALLBACK),
                            value("targetUrl", targetUrl),
                            value("httpStatus", 409));
                    // 409 = AckNoCallback — counts as a successful delivery
                    dispatcherMetrics.incrementSuccess();
                    return true;
                }
                sample.stop(dispatcherMetrics.callbackTimerClientError());
                throw e;
            }

        } catch (RestClientException e) {
            log.error("{}", value("event", LogEvent.CALLBACK_ERROR),
                    value("errorMessage", e.getMessage()), e);
            throw e;
        } catch (Exception e) {
            log.error("{}", value("event", LogEvent.CALLBACK_ERROR),
                    value("errorMessage", e.getMessage()), e);
            dispatcherMetrics.incrementFailure();
            throw new CallbackDeliveryException("Failed to send callback", e);
        }
    }

    /**
     * Called by Spring Retry after all retry attempts for {@link RestClientException} are exhausted.
     */
    @Recover
    public boolean recoverSendCallback(RestClientException e, String eventJson) {
        log.error("{}", value("event", LogEvent.CALLBACK_ERROR),
                value("reason", "all retry attempts exhausted"),
                value("errorMessage", e.getMessage()), e);
        dispatcherMetrics.incrementFailure();
        throw new CallbackDeliveryException("Callback delivery failed after all retries", e);
    }

    /**
     * Validates the callback URL against SSRF attack vectors.
     */
    private void validateCallbackUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed callback URL: " + url, e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("https") && !scheme.equals("http"))) {
            throw new IllegalArgumentException("Invalid callback URL scheme: " + scheme);
        }

        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("Invalid callback URL: no host");
        }

        // TODO (DNS TOCTOU): this DNS resolution is for SSRF validation only. The HTTP
        //  client resolves the hostname again at connection time, so a DNS rebinding
        //  attack could swap a public IP for a private one between these two calls.
        //  A production-grade fix requires a custom ClientHttpRequestFactory that pins
        //  the resolved IP into the socket connection (i.e., resolve-once and connect
        //  to the IP directly).
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
                throw new IllegalArgumentException(
                        "Callback URL points to private/loopback address: " + host);
            }
        } catch (java.net.UnknownHostException e) {
            // Fail-closed: if the host cannot be resolved we cannot verify it is safe.
            // Proceeding would allow an attacker to register an unresolvable hostname
            // that bypasses the SSRF guard.
            log.error("{}", value("event", LogEvent.CALLBACK_ERROR),
                    value("reason", "callback URL host unresolvable — rejecting request"),
                    value("host", host));
            throw new IllegalArgumentException("Callback URL host cannot be resolved: " + host, e);
        }
    }

    /**
     * Parses the Beckn v2.1 ACK/NACK response body.
     */
    private void parseAckResponse(ResponseEntity<String> response, String targetUrl) {
        var statusCode = response.getStatusCode();
        var body = response.getBody();

        if (body == null || body.isBlank()) {
            log.warn("{}", value("event", LogEvent.CALLBACK_ACK),
                    value("targetUrl", targetUrl),
                    value("httpStatus", statusCode.value()),
                    value("reason", "empty response body"));
            return;
        }
        try {
            var responseNode = objectMapper.readTree(body);
            var status = responseNode.path(BecknFields.STATUS).asText();
            if ("NACK".equals(status)) {
                var error = responseNode.path(BecknFields.ERROR);
                var errorCode = error.path(BecknFields.ERROR_CODE).asText();
                var errorMessage = error.path(BecknFields.ERROR_MESSAGE).asText();
                log.warn("{}", value("event", LogEvent.CALLBACK_NACK),
                        value("targetUrl", targetUrl),
                        value("httpStatus", statusCode.value()),
                        value("errorCode", errorCode),
                        value("errorMessage", errorMessage),
                        value("responseBody", body));
            } else {
                log.debug("{}", value("event", LogEvent.CALLBACK_ACK),
                        value("targetUrl", targetUrl),
                        value("httpStatus", statusCode.value()));
            }
        } catch (Exception e) {
            log.warn("{}", value("event", LogEvent.CALLBACK_ACK),
                    value("targetUrl", targetUrl),
                    value("httpStatus", statusCode.value()),
                    value("reason", "could not parse response body"),
                    value("parseError", e.getMessage()));
        }
    }

    /** Strips a trailing slash from a base URL so path constants can always start with '/'. */
    private static String normalizeBaseUrl(String url) {
        return (url != null && url.endsWith("/")) ? url.substring(0, url.length() - 1) : url;
    }

    /** Truncates a string to a maximum length for safe logging. */
    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...[truncated]";
    }
}
