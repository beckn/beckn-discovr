package org.beckn.discover.service.authorization;

import com.fasterxml.jackson.databind.JsonNode;
import org.beckn.discover.common.ErrorCodes;
import org.beckn.discover.common.ErrorMessages;
import org.beckn.discover.config.DiscoveryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Registry Service
 *
 * <p>Handles all network interactions with the Beckn Registry.
 * Pure Network I/O: fetches raw PEM strings without parsing or caching.
 * Parsing and caching are delegated to higher-level services.</p>
 *
 * <p>Uses {@link RestClient} (synchronous).  Retry logic is an inline loop
 * that retries on I/O errors and 5xx responses, leaving non-retryable 4xx
 * responses to propagate immediately.</p>
 */
@Service
public class RegistryService {

    private static final Logger logger = LoggerFactory.getLogger(RegistryService.class);

    private final DiscoveryProperties discoveryProperties;
    private final AuthUtils authUtils;
    private final RestClient restClient;

    public RegistryService(
            DiscoveryProperties discoveryProperties,
            AuthUtils authUtils,
            RestClient.Builder restClientBuilder) {
        this.discoveryProperties = discoveryProperties;
        this.authUtils = authUtils;

        int timeoutSeconds = discoveryProperties.getRegistryAuth().getTimeoutSeconds();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        this.restClient = restClientBuilder.requestFactory(factory).build();
    }

    /**
     * Fetches raw public key PEM from registry (no caching).
     *
     * @param subscriberId  Subscriber ID
     * @param uniqueKeyId   Key ID
     * @param transactionId Transaction ID for logging
     * @return raw PEM string
     */
    public String fetchPublicKeyPem(String subscriberId, String uniqueKeyId, String transactionId) {
        try {
            String registryUrl = authUtils.constructRegistryUrl(subscriberId, uniqueKeyId);
            logger.debug("Fetching public key from: {} [txnId: {}]", registryUrl, transactionId);

            String pem = fetchPublicKeyFromRegistry(registryUrl);

            if (pem == null) {
                throw authUtils.authError(ErrorMessages.REGISTRY_RECORD_NOT_FOUND,
                        ErrorCodes.SEC_KEY_NOT_FOUND, "authorization",
                        transactionId, HttpStatus.UNAUTHORIZED);
            }

            return pem;

        } catch (ErrorResponseException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to fetch public key [txnId: {}]: {}", transactionId, e.getMessage());
            throw authUtils.authError("Failed to fetch public key: " + e.getMessage(),
                    ErrorCodes.NET_INTERNAL_ERROR, "server",
                    transactionId, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Executes the HTTP request to the registry with inline retry.
     *
     * <p>Retries on {@link ResourceAccessException} (I/O errors, timeouts) and
     * {@link HttpServerErrorException} (5xx).  Returns {@code null} on 404.
     * Non-retryable 4xx errors propagate immediately.</p>
     */
    private String fetchPublicKeyFromRegistry(String url) {
        String bearerToken = discoveryProperties.getRegistryAuth().getRegistryToken();
        int maxAttempts    = discoveryProperties.getRegistryAuth().getRetryAttempts() + 1;

        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                JsonNode root = restClient.get()
                        .uri(url)
                        .accept(MediaType.APPLICATION_JSON)
                        .headers(h -> {
                            if (bearerToken != null && !bearerToken.isEmpty()) {
                                h.setBearerAuth(bearerToken);
                            }
                        })
                        .retrieve()
                        .body(JsonNode.class);

                return extractPublicKeyString(root);

            } catch (HttpClientErrorException.NotFound e) {
                return null; // 404 = subscriber not found, no retry

            } catch (HttpClientErrorException e) {
                throw e; // other 4xx — not retryable

            } catch (HttpServerErrorException | ResourceAccessException e) {
                lastException = e;
                logger.warn("Registry fetch attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        throw new RuntimeException(
                "Registry fetch failed after " + maxAttempts + " attempt(s)", lastException);
    }

    private String extractPublicKeyString(JsonNode root) {
        if (root == null) return null;

        JsonNode details = root.path("data");
        if (!details.isMissingNode() && details.has("details")) {
            details = details.path("details");
        }

        String key = null;
        if (details.has("signing_public_key"))
            key = details.get("signing_public_key").asText();
        else if (details.has("publicKey"))
            key = details.get("publicKey").asText();

        return (key != null && !key.isBlank()) ? key.trim() : null;
    }
}
