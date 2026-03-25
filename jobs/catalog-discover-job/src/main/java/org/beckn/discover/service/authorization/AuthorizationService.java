package org.beckn.discover.service.authorization;

import java.security.PublicKey;

import org.beckn.discover.common.ErrorCodes;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.service.CacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;

import static net.logstash.logback.argument.StructuredArguments.value;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Authorization Service
 * Orchestrates Beckn HTTP Signature validation.
 */
@Service
public class AuthorizationService {

    private static final Logger logger = LoggerFactory.getLogger(AuthorizationService.class);

    private final DiscoveryProperties discoveryProperties;
    private final AuthUtils authUtils;
    private final CryptoUtils cryptoUtils;
    private final RegistryService registryService;
    private final CacheService cacheService;

    public AuthorizationService(DiscoveryProperties discoveryProperties,
            AuthUtils authUtils, CryptoUtils cryptoUtils, RegistryService registryService,
            CacheService cacheService) {
        this.discoveryProperties = discoveryProperties;
        this.authUtils = authUtils;
        this.cryptoUtils = cryptoUtils;
        this.registryService = registryService;
        this.cacheService = cacheService;
    }

    /**
     * Authorize Beckn HTTP Signature from request with raw body.
     * <p>
     * <b>High-Performance Authorization Flow:</b>
     * <ol>
     * <li><b>Lightweight Context Extraction:</b> Extracts key IDs (transaction_id,
     * message_id) directly
     * from the JSON tree (O(1)) without incurring the cost of full POJO
     * deserialization.</li>
     * <li><b>Header Validation:</b> Parses and validates the Authorization header
     * using zero-allocation string ops.</li>
     * <li><b>Timestamp Checks:</b> Enforces strict 'created' and 'expires' window
     * to prevent replay attacks.</li>
     * <li><b>Look-Aside Caching:</b> Checks {@link CacheService} for a
     * <b>pre-parsed</b> {@link PublicKey} object.
     * If missing, fetches raw PEM from Registry and parses it once.</li>
     * <li><b>Zero-Copy Verification:</b> Performs Ed25519 signature verification
     * directly using the cached PublicKey,
     * skipping repetitive key parsing.</li>
     * </ol>
     * 
     * @param rawBody     The raw request body string (preserves original formatting)
     * @param requestNode The parsed JSON request body
     * @param headers     HTTP headers containing Authorization
     * @throws ErrorResponseException if authorization fails (400/401) with
     *                                Beckn-specific error codes.
     */
    public void authorizeRequest(String rawBody, JsonNode requestNode, HttpHeaders headers) {
        if (!discoveryProperties.getRegistryAuth().isEnabled()) {
            logger.debug("Registry authorization is disabled, skipping authorization");
            return;
        }

        // [Step 0] Lightweight Context Extraction
        // Optimization: Avoid full Context deserialization here.
        // We only need IDs for logging/tracing. Full schema validation happens later in
        // DiscoveryController.
        String transactionId = extractTransactionId(requestNode);
        String messageId = extractMessageId(requestNode);

        // [Step 1] Parse and Validate Authorization Header
        // Extracts subscriberId, uniqueKeyId, timestamps, and signature from the header
        // string.
        String authHeader = authUtils.extractAuthorizationHeader(headers, transactionId);
        AuthUtils.ParsedAuthHeader parsedHeader = authUtils.parseAuthHeader(authHeader, transactionId);

        // [Step 2] Validate Timestamps
        // Ensures the request is not expired and wasn't created in the future.
        authUtils.validateTimestamps(parsedHeader, transactionId);

        // [Step 3] Fetch Public Key (Cached & Parsed)
        // Returns a java.security.PublicKey object (cached), avoiding expensive PEM
        // parsing on every request.
        PublicKey publicKey = getPublicKeyWithCache(parsedHeader.subscriberId(), parsedHeader.uniqueKeyId(),
                transactionId);

        // [Step 4] Verify Signature
        // Uses the PublicKey object to verify the signed payload (Ed25519).
        verifySignature(rawBody, parsedHeader, publicKey, transactionId, messageId);

        logger.info(LogEvent.AUTH_PASSED,
                value("transactionId", transactionId),
                value("messageId", messageId),
                value("subscriberId", parsedHeader.subscriberId()));
    }

    private void verifySignature(String rawBody, AuthUtils.ParsedAuthHeader auth, PublicKey publicKey,
            String transactionId, String messageId) {
        try {
            // Generate digest from raw body (preserves original formatting)
            String digestBase64 = cryptoUtils.generateHash(rawBody);

            // Create signing string
            String signingString = cryptoUtils.createSigningString(auth.created(), auth.expires(), digestBase64);

            // Verify
            if (!cryptoUtils.verifySignature(signingString, auth.signature(), publicKey)) {
                throw authUtils.authError("Signature verification failed", ErrorCodes.SEC_SIGNATURE_INVALID,
                        "authorization", transactionId, HttpStatus.UNAUTHORIZED);
            }
        } catch (ErrorResponseException e) {
            throw e;
        } catch (Exception e) {
            logger.error(LogEvent.AUTH_FAILED,
                    value("transactionId", transactionId),
                    value("messageId", messageId),
                    value("error", e.getMessage()),
                    e);
            throw authUtils.authError("Internal crypto error: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR, "server",
                    transactionId, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Get Public Key with Look-Aside Caching.
     * Aligned with Node.js getPublicKeyWithCache.
     */
    private PublicKey getPublicKeyWithCache(String subscriberId, String uniqueKeyId, String transactionId) {
        String cacheKey = subscriberId + ":" + uniqueKeyId;
        boolean isCacheEnabled = discoveryProperties.getRegistryAuth().isCacheEnabled();

        // Fetch from centralized CacheService if enabled
        if (isCacheEnabled) {
            PublicKey cachedKey = cacheService.get(cacheKey, PublicKey.class);
            if (cachedKey != null) {
                return cachedKey;
            }
        }

        // Cache Miss: Fetch PEM from Registry (Network I/O)
        String pem = registryService.fetchPublicKeyPem(subscriberId, uniqueKeyId, transactionId);

        // Parse into PublicKey Object (CPU intensive)
        PublicKey publicKey = cryptoUtils.parsePublicKey(pem);

        // Store Object in CacheService if enabled
        if (isCacheEnabled) {
            cacheService.put(cacheKey, publicKey);
        }

        return publicKey;
    }

    // --- Helpers ---

    private String extractTransactionId(JsonNode requestNode) {
        return requestNode.path("context").path("transaction_id").asText("unknown");
    }

    private String extractMessageId(JsonNode requestNode) {
        return requestNode.path("context").path("message_id").asText("unknown");
    }
}
