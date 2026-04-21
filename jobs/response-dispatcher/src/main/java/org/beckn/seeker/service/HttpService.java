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
 * Service for sending HTTP requests to BAP/BPP endpoints.
 *
 * <p>Resolves callback URLs exclusively from the DeDi Registry via
 * {@link BecknAuth#getRegistryEntry} using subscriber identity propagated
 * as Kafka headers. No context-based fallback — registry must be configured.</p>
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
     * Resolves callback URL from the DeDi Registry when subscriber identity is available.
     * This method is retryable based on configuration.
     *
     * @param eventJson    the full response event as a JSON string
     * @param subscriberId org-level identity from Kafka header; must not be null or blank
     * @param recordId     key-level identity from Kafka header; must not be null or blank
     * @return true if the callback was successful, false otherwise
     * @throws CallbackDeliveryException if subscriberId or recordId is null/blank, or delivery fails
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
    public boolean sendCallback(String eventJson, String subscriberId, String recordId) {
        try {
            JsonNode rootNode = objectMapper.readTree(eventJson);
            JsonNode context = rootNode.path(BecknFields.CONTEXT);

            if (context.isMissingNode()) {
                log.error("{}", value("event", LogEvent.CALLBACK_ERROR),
                        value("reason", "missing context"),
                        value("eventJson", truncate(eventJson, 2000)));
                throw new IllegalArgumentException("Invalid event structure for BAP notification");
            }

            String action = context.path(BecknFields.ACTION).asText();
            String targetUrl = resolveTargetUrl(action, subscriberId, recordId);

            // SSRF guard — defense-in-depth even for registry-resolved URLs
            if (httpClientProperties.urlValidationEnabled()) {
                try {
                    validateCallbackUrl(targetUrl);
                } catch (IllegalArgumentException ssrfEx) {
                    dispatcherMetrics.recordSsrfBlocked();
                    throw ssrfEx;
                }
            }

            // Log after SSRF check passes — avoid leaking blocked URLs at INFO level
            log.info("{}", value("event", LogEvent.CALLBACK_RESOLVED),
                    value("action", action),
                    value("targetUrl", targetUrl),
                    value("subscriberId", sanitize(subscriberId)));

            // Normalize JSON to compact format for consistent signature validation
            String requestBody = objectMapper.writeValueAsString(rootNode);

            // Prepare headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Propagate tags from MDC to outbound HTTP
            String tags = org.slf4j.MDC.get("tags");
            if (tags != null && !tags.isBlank()) {
                headers.set("X-Tags", tags);
            }

            // Sign payload using SDK
            if (signingProperties.enabled()) {
                log.debug("{}", value("event", LogEvent.SIGNATURE_INIT),
                        value("targetUrl", targetUrl),
                        value("action", action));
                headers.set("Authorization", becknAuth.signPayload(requestBody));
                log.debug("{}", value("event", LogEvent.SIGNATURE_GENERATED),
                        value("targetUrl", targetUrl));
            }

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            Timer.Sample sample = Timer.start();

            try {
                log.info("{}", value("event", LogEvent.CALLBACK_SENT),
                        value("targetUrl", targetUrl));

                ResponseEntity<String> response = restTemplate.exchange(
                        targetUrl, HttpMethod.POST, entity, String.class);

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
    public boolean recoverSendCallback(RestClientException e, String eventJson,
                                       String subscriberId, String recordId) {
        log.error("{}", value("event", LogEvent.CALLBACK_ERROR),
                value("reason", "all retry attempts exhausted"),
                value("errorMessage", e.getMessage()), e);
        dispatcherMetrics.incrementFailure();
        throw new CallbackDeliveryException("Callback delivery failed after all retries", e);
    }

    /**
     * Fallback for non-retryable exceptions (registry failures, auth errors, etc.).
     */
    @Recover
    public boolean recoverSendCallback(Exception e, String eventJson,
                                       String subscriberId, String recordId) {
        log.error("{}", value("event", LogEvent.CALLBACK_ERROR),
                value("reason", "non-retryable failure"),
                value("errorMessage", e.getMessage()), e);
        dispatcherMetrics.incrementFailure();
        throw new CallbackDeliveryException("Callback delivery failed: " + e.getMessage(), e);
    }

    /**
     * Resolves the target callback URL from the DeDi Registry.
     *
     * <p>Subscriber identity (subscriberId + recordId) must be present as Kafka headers.
     * No context-based fallback — registry is the single source of truth for callback URLs.</p>
     */
    private String resolveTargetUrl(String action, String subscriberId, String recordId) {
        String endpoint = resolveEndpointPath(action);

        if (subscriberId == null || subscriberId.isBlank()
                || recordId == null || recordId.isBlank()) {
            log.error("{}", value("event", LogEvent.CALLBACK_ERROR),
                    value("reason", "missing subscriber identity — cannot resolve callback URL"),
                    value("action", action),
                    value("subscriberId", sanitize(subscriberId)),
                    value("recordId", sanitize(recordId)));
            throw new IllegalArgumentException(
                    "Subscriber identity required for callback URL resolution: subscriberId="
                    + sanitize(subscriberId) + " recordId=" + sanitize(recordId));
        }

        var entry = becknAuth.getRegistryEntry(subscriberId, recordId);
        String baseUrl = entry.subscriberUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            log.error("{}", value("event", LogEvent.REGISTRY_FAILED),
                    value("subscriberId", sanitize(subscriberId)),
                    value("recordId", sanitize(recordId)),
                    value("reason", "registry returned blank URL"));
            throw new IllegalArgumentException(
                    "Registry returned blank URL for subscriberId=" + sanitize(subscriberId)
                    + " recordId=" + sanitize(recordId));
        }
        log.info("{}", value("event", LogEvent.REGISTRY_RESOLVED),
                value("subscriberId", sanitize(subscriberId)),
                value("recordId", sanitize(recordId)));
        return normalizeBaseUrl(baseUrl) + endpoint;
    }

    private String resolveEndpointPath(String action) {
        if (ACTION_ON_DISCOVER.equals(action)) {
            return ON_DISCOVER_ENDPOINT;
        } else if (ACTION_ON_CATALOG_PUBLISH.equals(action)) {
            return ON_PUBLISH_ENDPOINT;
        }
        throw new IllegalArgumentException("Unknown or unsupported action: " + action);
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
     * Parses the Beckn v2.0 ACK/NACK response body.
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

    /** Strips control characters to prevent log injection from user-supplied strings. */
    private static String sanitize(String s) {
        if (s == null) return null;
        return s.replaceAll("[\\p{Cc}]", "");
    }
}
