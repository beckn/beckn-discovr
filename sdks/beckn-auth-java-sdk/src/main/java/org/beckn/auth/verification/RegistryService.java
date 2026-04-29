package org.beckn.auth.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.auth.BecknAuthConfig;
import org.beckn.auth.cache.Cache;
import org.beckn.auth.crypto.CryptoService;
import org.beckn.auth.exception.BecknAuthException;
import org.beckn.auth.logging.Logger;
import org.beckn.auth.util.ErrorCodes;
import org.beckn.auth.util.ErrorMessages;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Fetches and caches Ed25519 public keys from the Beckn Registry.
 * <p>
 * This service is only used during the signature verification flow.
 * It is never involved in signature generation.
 * </p>
 *
 * <h3>Responsibilities</h3>
 * <ul>
 * <li>Cache-aside lookup: returns a cached {@link RegistryEntry} (public key +
 * subscriber URL) on hit to avoid repeated network calls (default TTL: 30 days)</li>
 * <li>HTTP GET to the registry with configurable timeout and exponential
 * backoff retry (retries on 429 and 5xx, fails fast on other 4xx)</li>
 * <li>JSON response parsing: explicit path navigation
 * {@code data → data.details → signing_public_key | publicKey}
 * matching discovery-service-v2 behaviour</li>
 * <li>Key state validation: rejects keys whose {@code state} field is not
 * {@code "live"}</li>
 * </ul>
 */
public final class RegistryService {

    private static final int HTTP_STATUS_OK = 200;
    private static final int HTTP_STATUS_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_STATUS_SERVER_ERROR_THRESHOLD = 500;
    private static final String PEM_HEADER = "-----BEGIN PUBLIC KEY-----";
    private static final String PEM_FOOTER = "-----END PUBLIC KEY-----";
    /** Allowlist: alphanumeric, dots, hyphens, underscores, colons only. Rejects path traversal and redirects. */
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9._\\-:]+$");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final Cache cache;
    private final BecknAuthConfig config;
    private final CryptoService cryptoService;
    private final Logger logger;
    /** Pre-computed URL prefix to avoid repeated string manipulation on every request. */
    private final String registryBaseUrlPrefix;

    /**
     * Constructs a RegistryService. The JDK {@link HttpClient} is initialized
     * once with a connect timeout matching the configured HTTP timeout.
     *
     * @param config        the SDK configuration (registry URL, token, retry
     *                      settings)
     * @param cryptoService the crypto service used to parse raw public key bytes
     */
    public RegistryService(BecknAuthConfig config, CryptoService cryptoService) {
        this.config = config;
        this.cryptoService = cryptoService;
        this.cache = config.getCache();
        this.logger = config.getLogger();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();

        // Pre-compute URL prefix; ensures trailing slash exactly once
        String baseUrl = config.getRegistryBaseUrl();
        this.registryBaseUrlPrefix = (baseUrl != null && baseUrl.endsWith("/")) ? baseUrl : (baseUrl + "/");
    }

    /**
     * Returns the Ed25519 {@link PublicKey} for the given subscriber and key ID.
     * <p>
     * Checks the cache first. On a miss, fetches from the registry with retry,
     * validates key state, parses the key, caches it, and returns it.
     * The subscriber URL ({@code details.url}) is also cached alongside the
     * public key from the same registry response.
     * </p>
     *
     * @param subscriberId the subscriber ID extracted from the Authorization header keyId
     * @param uniqueKeyId  the unique key ID extracted from the Authorization header keyId
     * @return the parsed Ed25519 public key, ready for signature verification
     * @throws BecknAuthException with {@code SEC_KEY_NOT_FOUND} (401) if the registry
     *                            returns a non-200 non-retryable response or no key is found
     * @throws BecknAuthException with {@code SEC_KEY_EXPIRED_OR_REVOKED} (401) if key
     *                            state is not {@code "live"}
     * @throws BecknAuthException with {@code NET_INTERNAL_ERROR} (500) if all retries
     *                            are exhausted
     */
    public PublicKey getPublicKey(String subscriberId, String uniqueKeyId) {
        return getRegistryEntry(subscriberId, uniqueKeyId).publicKey();
    }

    /**
     * Returns the cached {@link RegistryEntry} for the given subscriber and key ID.
     * <p>
     * Checks the cache first. On a miss, fetches from the registry with retry,
     * validates key state, parses the key, extracts the subscriber URL, caches the
     * combined entry, and returns it.
     * </p>
     *
     * @param subscriberId the subscriber ID extracted from the Authorization header keyId
     * @param uniqueKeyId  the unique key ID extracted from the Authorization header keyId
     * @return the cached registry entry containing public key and subscriber URL
     * @throws BecknAuthException on registry errors, non-live key state, or missing key
     */
    public RegistryEntry getRegistryEntry(String subscriberId, String uniqueKeyId) {
        String cacheKey = buildCacheKey(subscriberId, uniqueKeyId);

        // 1. Cache-aside: return immediately on hit
        RegistryEntry cached = cache.get(cacheKey, RegistryEntry.class);
        if (cached != null) {
            logger.debug("Cache hit for registry entry | subscriber=" + subscriberId
                    + " | uniqueKeyId=" + uniqueKeyId);
            return cached;
        }
        logger.debug("Cache miss, fetching from registry"
                + " | subscriber=" + subscriberId + " | uniqueKeyId=" + uniqueKeyId);

        // 2. Fetch from registry with exponential backoff retry
        String registryUrl = buildRegistryUrl(subscriberId, uniqueKeyId);
        String responseJson = fetchWithRetry(registryUrl, subscriberId);

        // 3. Validate key state must be "live"
        validateKeyState(responseJson, subscriberId);

        // 4. Extract the public key string from JSON
        String rawPublicKeyString = extractPublicKeyField(responseJson, subscriberId);

        // 5. Wrap in PEM headers if needed so CryptoService can parse it
        String pemPublicKey = formatToPem(rawPublicKeyString);

        // 6. Parse raw bytes into a java.security.PublicKey object
        PublicKey publicKey = cryptoService.parsePublicKey(pemPublicKey);

        // 7. Extract subscriber URL from the same response
        String subscriberUrl = extractSubscriberUrl(responseJson, subscriberId);

        // 8. Cache combined entry
        var entry = new RegistryEntry(publicKey, subscriberUrl);
        cache.set(cacheKey, entry);

        logger.info("Registry entry fetched, parsed, and cached"
                + " | subscriber=" + subscriberId + " | uniqueKeyId=" + uniqueKeyId);
        return entry;
    }

    /**
     * Returns the canonical callback base URI ({@code details.url}) for the
     * given subscriber from the DeDi registry.
     *
     * @param subscriberId the subscriber ID
     * @param uniqueKeyId  the unique key ID
     * @return the subscriber's canonical URL, or {@code null} if the field was
     *         absent in the registry response
     * @throws BecknAuthException on registry errors or non-live key state
     */
    public String getSubscriberUrl(String subscriberId, String uniqueKeyId) {
        return getRegistryEntry(subscriberId, uniqueKeyId).subscriberUrl();
    }

    // ─── Private helpers ────────────────────────────────────────────────────────

    /**
     * Builds a cache key from subscriber and key IDs.
     *
     * @param subscriberId the subscriber ID
     * @param uniqueKeyId  the unique key ID
     * @return cache key string
     */
    private String buildCacheKey(String subscriberId, String uniqueKeyId) {
        return subscriberId + "|" + uniqueKeyId;
    }

    /**
     * Constructs the full registry lookup URL.
     * Format: {@code {baseUrl}/{subscriberId}/{registryName}/{uniqueKeyId}}
     *
     * @param subscriberId the subscriber ID
     * @param uniqueKeyId  the unique key ID
     * @return the full registry URL string
     */
    private String buildRegistryUrl(String subscriberId, String uniqueKeyId) {
        if (!SAFE_ID_PATTERN.matcher(subscriberId).matches()) {
            throw BecknAuthException.invalidHeader(
                    "Invalid subscriberId format in keyId: " + subscriberId, ErrorCodes.SEC_SIGNATURE_INVALID);
        }
        if (!SAFE_ID_PATTERN.matcher(uniqueKeyId).matches()) {
            throw BecknAuthException.invalidHeader(
                    "Invalid uniqueKeyId format in keyId: " + uniqueKeyId, ErrorCodes.SEC_SIGNATURE_INVALID);
        }
        return registryBaseUrlPrefix + subscriberId + "/" + config.getRegistryName() + "/" + uniqueKeyId;
    }

    /**
     * Fetches JSON from the registry with exponential backoff retry.
     *
     * <h3>Retry Policy</h3>
     * <ul>
     * <li>200 OK → return response body immediately</li>
     * <li>429 or 5xx → wait and retry with doubled delay (capped at maxDelayMs)</li>
     * <li>Other 4xx → fail immediately (not retryable — indicates bad request)</li>
     * <li>{@link IOException} / {@link InterruptedException} → retry with backoff</li>
     * </ul>
     *
     * @param registryUrl  the fully constructed registry endpoint URL
     * @param subscriberId the subscriber ID, used for logging context only
     * @return the raw JSON response body string
     * @throws BecknAuthException with {@code SEC_KEY_NOT_FOUND} (401) on non-retryable 4xx
     * @throws BecknAuthException with {@code NET_INTERNAL_ERROR} (500) after all retries exhausted
     */
    private String fetchWithRetry(String registryUrl, String subscriberId) {
        int maxAttempts = config.getRetryAttempts();
        int initialDelayMs = config.getRetryInitialDelayMs();
        int maxDelayMs = config.getRetryMaxDelayMs();

        Exception lastNetworkException = null;
        HttpRequest request = buildHttpRequest(registryUrl);

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();

                if (statusCode == HTTP_STATUS_OK) {
                    logger.debug("Registry responded 200 OK"
                            + " | subscriber=" + subscriberId
                            + " | attempt=" + (attempt + 1));
                    return response.body();
                }

                if (isRetryableStatusCode(statusCode)) {
                    long delayMs = Math.min((long) initialDelayMs * (1L << attempt), maxDelayMs);
                    logger.warn("Registry returned retryable status, will retry"
                            + " | status=" + statusCode
                            + " | subscriber=" + subscriberId
                            + " | attempt=" + (attempt + 1) + "/" + maxAttempts
                            + " | retryDelayMs=" + delayMs
                            + " | url=" + registryUrl);
                    sleepBeforeRetry(attempt, initialDelayMs, maxDelayMs);
                    continue;
                }

                // Non-retryable client error (404, 401, 403 etc.)
                logger.error("Registry returned non-retryable error, aborting"
                        + " | status=" + statusCode
                        + " | subscriber=" + subscriberId
                        + " | url=" + registryUrl);
                throw BecknAuthException.keyNotFound(
                        ErrorMessages.REGISTRY_RECORD_NOT_FOUND + ": HTTP " + statusCode);

            } catch (BecknAuthException exception) {
                throw exception; // propagate immediately — do not retry client errors
            } catch (IOException | InterruptedException exception) {
                lastNetworkException = exception;
                long delayMs = Math.min((long) initialDelayMs * (1L << attempt), maxDelayMs);
                logger.warn("Registry request failed with network error, will retry"
                        + " | subscriber=" + subscriberId
                        + " | attempt=" + (attempt + 1) + "/" + maxAttempts
                        + " | retryDelayMs=" + delayMs
                        + " | error=" + exception.getMessage()
                        + " | url=" + registryUrl);
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                sleepBeforeRetry(attempt, initialDelayMs, maxDelayMs);
            }
        }

        logger.error("All registry retry attempts exhausted"
                + " | subscriber=" + subscriberId
                + " | attempts=" + maxAttempts
                + " | url=" + registryUrl);
        throw BecknAuthException.registryError(
                ErrorMessages.REGISTRY_CONNECTION_ERROR + ": " + registryUrl, lastNetworkException);
    }

    /**
     * Builds the HTTP GET request with optional Bearer token header.
     *
     * @param registryUrl the registry endpoint URL
     * @return the constructed {@link HttpRequest}
     */
    private HttpRequest buildHttpRequest(String registryUrl) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(registryUrl))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .GET();

        String apiToken = config.getRegistryToken();
        if (apiToken != null && !apiToken.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiToken);
            logger.debug("Registry request will include Bearer token authentication");
        }

        return requestBuilder.build();
    }

    /**
     * Returns {@code true} for HTTP status codes that should be retried:
     * 429 (rate limited) and 5xx (server errors).
     *
     * @param statusCode the HTTP response status code
     * @return whether the request should be retried
     */
    private boolean isRetryableStatusCode(int statusCode) {
        return statusCode == HTTP_STATUS_TOO_MANY_REQUESTS
                || statusCode >= HTTP_STATUS_SERVER_ERROR_THRESHOLD;
    }

    /**
     * Sleeps the current thread using exponential backoff, capped at {@code maxDelayMs}.
     * Delay formula: {@code min(initialDelayMs * 2^attempt, maxDelayMs)}.
     *
     * @param attempt        zero-based attempt index
     * @param initialDelayMs initial delay in milliseconds (doubles each attempt)
     * @param maxDelayMs     ceiling on the delay in milliseconds
     */
    private void sleepBeforeRetry(int attempt, int initialDelayMs, int maxDelayMs) {
        long delayMs = Math.min((long) initialDelayMs * (1L << attempt), maxDelayMs);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Validates that the key's {@code state} field in the registry JSON response
     * is {@code "live"}. If the field is absent, no error is thrown (permissive).
     * <p>
     * Navigates the explicit JSON path: {@code data → data.details → state},
     * matching discovery-service-v2 behaviour.
     * </p>
     *
     * @param responseJson the raw registry JSON response string
     * @param subscriberId the subscriber ID, used for error logging context
     * @throws BecknAuthException with {@code SEC_KEY_EXPIRED_OR_REVOKED} (401) if state != "live"
     */
    private void validateKeyState(String responseJson, String subscriberId) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseJson);
            JsonNode details = root.path("data");
            if (!details.isMissingNode() && details.has("details")) {
                details = details.path("details");
            }
            if (details.isArray() && !details.isEmpty()) {
                details = details.get(0);
            }
            if (details.has("state")) {
                String state = details.get("state").asText();
                if (!"live".equalsIgnoreCase(state)) {
                    logger.error("Public key is not in 'live' state, rejecting"
                            + " | subscriber=" + subscriberId
                            + " | keyState=" + state);
                    throw BecknAuthException.keyExpired(
                            ErrorMessages.AUTH_PUBLIC_KEY_EXPIRED + ": state=" + state);
                }
            }
        } catch (BecknAuthException exception) {
            throw exception;
        } catch (Exception exception) {
            // Non-fatal: if we cannot parse the state field, continue without rejecting
            logger.warn("Could not parse key state field from registry response"
                    + " | subscriber=" + subscriberId
                    + " | error=" + exception.getMessage());
        }
    }

    /**
     * Extracts the signing public key string from the registry JSON response.
     * <p>
     * Navigates the explicit path {@code data → data.details → signing_public_key | publicKey},
     * matching the discovery-service-v2 {@code extractPublicKeyString()} approach.
     * Recursive {@code findValue()} is intentionally avoided for predictability.
     * </p>
     *
     * @param responseJson the raw registry JSON response string
     * @param subscriberId the subscriber ID, used for error logging context
     * @return the raw public key string (Base64 or PEM)
     * @throws BecknAuthException with {@code SEC_KEY_NOT_FOUND} (401) if no key field is found
     */
    private String extractPublicKeyField(String responseJson, String subscriberId) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseJson);

            // Navigate explicit path: data → data.details (matches discovery-service-v2)
            JsonNode details = root.path("data");
            if (!details.isMissingNode() && details.has("details")) {
                details = details.path("details");
            }

            // If the resulting details node is an array, extract the first element
            if (details.isArray() && !details.isEmpty()) {
                details = details.get(0);
            }

            String publicKey = null;
            if (details.has("signing_public_key")) {
                publicKey = details.get("signing_public_key").asText();
            } else if (details.has("publicKey")) {
                publicKey = details.get("publicKey").asText();
            }

            if (publicKey != null && !publicKey.isBlank()) {
                return publicKey.trim();
            }

            logger.error("No public key field found in registry response"
                    + " | subscriber=" + subscriberId
                    + " | searchedFields=[signing_public_key, publicKey]"
                    + " | detailsKeys=" + getNodeFieldNames(details));
            throw BecknAuthException.keyNotFound(ErrorMessages.REGISTRY_RECORD_NOT_FOUND);

        } catch (BecknAuthException exception) {
            throw exception;
        } catch (Exception exception) {
            logger.error("Failed to parse public key from registry response"
                    + " | subscriber=" + subscriberId
                    + " | error=" + exception.getMessage());
            throw BecknAuthException.keyNotFound(ErrorMessages.REGISTRY_EMPTY_RESPONSE);
        }
    }

    /**
     * Returns a comma-separated list of field names present in a JSON node,
     * for diagnostic logging when the expected key field is missing.
     *
     * @param node the JSON node to inspect
     * @return field names as a string, e.g. {@code "[state, updated_at]"}
     */
    private String getNodeFieldNames(JsonNode node) {
        if (node == null || node.isMissingNode())
            return "[]";
        StringBuilder names = new StringBuilder("[");
        node.fieldNames().forEachRemaining(name -> names.append(name).append(", "));
        if (names.length() > 1)
            names.setLength(names.length() - 2);
        names.append("]");
        return names.toString();
    }

    /**
     * Extracts the subscriber's canonical URL ({@code details.url}) from the
     * registry JSON response.
     *
     * @param responseJson the raw registry JSON response string
     * @param subscriberId the subscriber ID, used for logging context
     * @return the URL string, or {@code null} if the field is absent or blank
     */
    private String extractSubscriberUrl(String responseJson, String subscriberId) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseJson);
            JsonNode details = root.path("data");
            if (!details.isMissingNode() && details.has("details")) {
                details = details.path("details");
            }
            if (details.isArray() && !details.isEmpty()) {
                details = details.get(0);
            }
            if (details.has("url")) {
                String url = details.get("url").asText(null);
                if (url != null && !url.isBlank()) {
                    return url.trim();
                }
            }
            logger.debug("No 'url' field found in registry response"
                    + " | subscriber=" + subscriberId);
            return null;
        } catch (Exception e) {
            logger.warn("Could not parse subscriber URL from registry response"
                    + " | subscriber=" + subscriberId + " | error=" + e.getMessage());
            return null;
        }
    }

    /**
     * Wraps a raw Base64 public key string in PEM headers if not already formatted.
     * {@link CryptoService#parsePublicKey} will then strip the headers and decode.
     *
     * @param rawPublicKey the raw Base64 or PEM-formatted public key string
     * @return a PEM-formatted public key string
     */
    private String formatToPem(String rawPublicKey) {
        String cleanedKey = rawPublicKey.trim().replace("\"", "");
        if (!cleanedKey.contains(PEM_HEADER)) {
            return PEM_HEADER + "\n" + cleanedKey + "\n" + PEM_FOOTER + "\n";
        }
        return cleanedKey;
    }
}
