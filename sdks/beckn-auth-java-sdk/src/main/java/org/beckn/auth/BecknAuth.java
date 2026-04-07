package org.beckn.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.auth.crypto.CryptoService;
import org.beckn.auth.exception.BecknAuthException;
import org.beckn.auth.logging.Logger;
import org.beckn.auth.model.ParsedAuthHeader;
import org.beckn.auth.signing.SignatureHeaderBuilder;
import org.beckn.auth.util.ErrorCodes;
import org.beckn.auth.util.ErrorMessages;
import org.beckn.auth.verification.AuthHeaderParser;
import org.beckn.auth.verification.RegistryService;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;

/**
 * Main entry point for the Beckn Auth SDK.
 * <p>
 * Provides two core operations:
 * <ul>
 * <li>{@link #generateAuthHeader(String)} — Signs an outgoing request body and
 * returns the complete {@code Authorization} header value.</li>
 * <li>{@link #verifySignature(String, String)} — Verifies an incoming
 * request's {@code Authorization} or {@code X-Gateway-Authorization} header.</li>
 * </ul>
 * </p>
 *
 * <h3>Fail-Fast Initialization</h3>
 * <p>
 * The constructor parses the private key and initializes the registry service
 * immediately, so misconfiguration is caught at startup rather than at first
 * request time.
 * </p>
 *
 * <h3>Thread Safety</h3>
 * <p>
 * This class is immutable and thread-safe. A single instance should be created
 * at application startup and shared across all request-handling threads.
 * </p>
 *
 * <h3>Usage — Signing</h3>
 * <pre>{@code
 * BecknAuth auth = new BecknAuth(BecknAuthConfig.builder()
 *     .signingEnabled(true)
 *     .subscriberId("example-bap.com")
 *     .keyIdSuffix("key-uuid")
 *     .privateKey("Base64EncodedKey")
 *     .build());
 *
 * String authHeader = auth.generateAuthHeader(rawJsonBody);
 * }</pre>
 *
 * <h3>Usage — Verification</h3>
 * <pre>{@code
 * BecknAuth auth = new BecknAuth(BecknAuthConfig.builder()
 *     .verificationEnabled(true)
 *     .registryBaseUrl("https://registry.becknprotocol.io/subscribers")
 *     .registryName("keys")
 *     .build());
 *
 * try {
 *     ParsedAuthHeader parsed = auth.verifySignature(authHeader, rawJsonBody);
 * } catch (BecknAuthException e) {
 *     AckResponse nack = AckResponse.fromException(e);
 * }
 * }</pre>
 *
 * <h3>Lifecycle</h3>
 * <p>
 * Call {@link #shutdown()} when the SDK instance is no longer needed (e.g. on
 * application shutdown) to release background cache cleanup threads.
 * </p>
 */
public final class BecknAuth {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Package-private for test access only
    final BecknAuthConfig config;

    private final CryptoService cryptoService;
    private final AuthHeaderParser headerParser;
    private final SignatureHeaderBuilder headerBuilder;
    private final RegistryService registryService;
    private final PrivateKey privateKey;
    private final Logger logger;

    /**
     * Constructs a new BecknAuth instance from the given configuration.
     * <p>
     * <b>Fail-fast behaviour:</b>
     * <ul>
     * <li>If signing is enabled, the private key is parsed immediately.</li>
     * <li>If verification is enabled, the registry service and HTTP client are
     * initialized immediately.</li>
     * </ul>
     * Misconfigured keys or unavailable algorithms will surface here, not at
     * first request time.
     * </p>
     *
     * @param config the immutable SDK configuration
     * @throws BecknAuthException with {@code INTERNAL_ERROR} (500) if the private
     *                            key cannot be parsed or Ed25519 is unavailable
     */
    public BecknAuth(BecknAuthConfig config) {
        this.config = config;
        this.logger = config.getLogger();
        this.cryptoService = new CryptoService(logger);
        this.headerParser = new AuthHeaderParser(logger);
        this.headerBuilder = new SignatureHeaderBuilder(logger);

        // Fail-fast: parse private key at construction time if signing is enabled
        this.privateKey = initializePrivateKey(config);

        // Fail-fast: initialize registry service only if verification is enabled
        this.registryService = config.isVerificationEnabled()
                ? new RegistryService(config, cryptoService)
                : null;

        logger.info("BecknAuth initialized"
                + " | signing: " + (config.isSigningEnabled() ? "ENABLED" : "DISABLED")
                + " | verification: " + (config.isVerificationEnabled() ? "ENABLED" : "DISABLED"));
    }

    // ─── Signing ────────────────────────────────────────────────────────────────

    /**
     * Generates a Beckn-compliant HTTP Signature {@code Authorization} header.
     * <p>
     * <b>IMPORTANT:</b> Pass the exact raw string that will be sent over the wire.
     * This method does NOT compact, pretty-print, or modify the JSON in any way.
     * The receiver hashes the exact bytes it receives, so any modification between
     * hashing here and transmission will cause verification to fail.
     * </p>
     * <p>
     * {@code transaction_id} and {@code message_id} are extracted automatically
     * from the {@code context} object in the request body for log correlation.
     * </p>
     *
     * @param rawRequestBody the exact unmodified request body string to sign
     * @return the complete {@code Authorization} header value, e.g.
     *         {@code Signature keyId="example-bap.com|key-uuid|ed25519",algorithm="ed25519",...}
     * @throws BecknAuthException with {@code INTERNAL_ERROR} (500) if signing is
     *                            not configured or signing fails
     */
    public String generateAuthHeader(String rawRequestBody) {
        BecknContext ctx = extractContext(rawRequestBody);

        logger.info("[SIGNING] STARTED | txnId: " + ctx.transactionId() + " | msgId: " + ctx.messageId());

        if (!config.isSigningEnabled()) {
            logger.error("[SIGNING] FAILED"
                    + " | txnId: " + ctx.transactionId()
                    + " | msgId: " + ctx.messageId()
                    + " | cause: signing not enabled (signingEnabled=false)");
            throw BecknAuthException.signatureGenerationFailed(
                    ErrorMessages.PRIVATE_KEY_NOT_CONFIGURED, ErrorCodes.INTERNAL_ERROR);
        }

        try {
            long createdTimestamp = Instant.now().getEpochSecond();
            long expiresTimestamp = createdTimestamp + config.getExpirySeconds();

            String bodyDigest = cryptoService.generateBlake2bHash(rawRequestBody);
            String signingString = headerBuilder.buildSigningString(createdTimestamp, expiresTimestamp, bodyDigest);
            String signature = cryptoService.signWithEd25519(signingString, privateKey);

            String authorizationHeader = headerBuilder.buildAuthorizationHeader(
                    config.getSubscriberId(), config.getKeyIdSuffix(),
                    createdTimestamp, expiresTimestamp, signature);

            logger.info("[SIGNING] SUCCESS"
                    + " | txnId: " + ctx.transactionId()
                    + " | msgId: " + ctx.messageId()
                    + " | subscriber: " + config.getSubscriberId());
            return authorizationHeader;

        } catch (BecknAuthException exception) {
            logger.error("[SIGNING] FAILED"
                    + " | txnId: " + ctx.transactionId()
                    + " | msgId: " + ctx.messageId()
                    + " | cause: " + exception.getCode()
                    + " | error: " + exception.getMessage());
            throw exception;
        } catch (Exception exception) {
            logger.error("[SIGNING] FAILED"
                    + " | txnId: " + ctx.transactionId()
                    + " | msgId: " + ctx.messageId()
                    + " | cause: unexpected error"
                    + " | error: " + exception.getMessage(), exception);
            throw BecknAuthException.signatureGenerationFailed("Failed to generate auth header", exception);
        }
    }

    // ─── Verification ───────────────────────────────────────────────────────────

    /**
     * Verifies an incoming Beckn HTTP Signature Authorization header against
     * the raw request body.
     *
     * <h3>Validation Steps</h3>
     * <ol>
     * <li>Parse and validate header format, required fields, and keyId structure.</li>
     * <li>Validate both keyId algorithm suffix and header {@code algorithm=} param
     * equal {@code ed25519}.</li>
     * <li>Validate {@code created} and {@code expires} timestamps within clock skew.</li>
     * <li>Fetch signer's public key from registry (cached after first lookup).</li>
     * <li>Reconstruct signing string from raw body and verify Ed25519 signature.</li>
     * </ol>
     *
     * @param authorizationHeader the {@code Authorization} or
     *                            {@code X-Gateway-Authorization} header value
     * @param rawRequestBody      the exact unmodified raw request body received
     *                            over the wire
     * @return the parsed header containing subscriberId, uniqueKeyId, algorithm,
     *         timestamps
     * @throws BecknAuthException with {@code INTERNAL_ERROR} (500) if verification
     *                            is not configured
     * @throws BecknAuthException with {@code SEC_SIGNATURE_MISSING} (400) if header
     *                            is absent
     * @throws BecknAuthException with {@code SEC_SIGNATURE_INVALID} (400/401) for
     *                            format, timestamp, or cryptographic failures
     * @throws BecknAuthException with {@code SEC_KEY_NOT_FOUND} (401) if key is
     *                            absent in registry
     * @throws BecknAuthException with {@code SEC_KEY_EXPIRED_OR_REVOKED} (401) if
     *                            key state is not live
     */
    public ParsedAuthHeader verifySignature(String authorizationHeader, String rawRequestBody) {
        BecknContext ctx = extractContext(rawRequestBody);

        logger.info("[VERIFICATION] STARTED | txnId: " + ctx.transactionId() + " | msgId: " + ctx.messageId());

        if (!config.isVerificationEnabled()) {
            logger.error("[VERIFICATION] FAILED"
                    + " | txnId: " + ctx.transactionId()
                    + " | msgId: " + ctx.messageId()
                    + " | cause: verification not enabled (registryBaseUrl not set)"
                    + " | authHeader: " + authorizationHeader);
            throw BecknAuthException.internalError(
                    "Verification not configured: registryBaseUrl and registryName are required");
        }

        try {
            ParsedAuthHeader parsedHeader = parseAndValidateHeader(authorizationHeader, ctx);
            PublicKey signerPublicKey = fetchSignerPublicKey(parsedHeader, ctx);
            verifyBodySignature(parsedHeader, rawRequestBody, signerPublicKey, authorizationHeader, ctx);

            logger.info("[VERIFICATION] SUCCESS"
                    + " | txnId: " + ctx.transactionId()
                    + " | msgId: " + ctx.messageId()
                    + " | subscriber: " + parsedHeader.subscriberId());
            return parsedHeader;

        } catch (BecknAuthException exception) {
            logger.error("[VERIFICATION] FAILED"
                    + " | txnId: " + ctx.transactionId()
                    + " | msgId: " + ctx.messageId()
                    + " | cause: " + exception.getCode()
                    + " | authHeader: " + authorizationHeader
                    + " | error: " + exception.getMessage());
            throw exception;
        } catch (Exception exception) {
            logger.error("[VERIFICATION] FAILED"
                    + " | txnId: " + ctx.transactionId()
                    + " | msgId: " + ctx.messageId()
                    + " | cause: unexpected error"
                    + " | authHeader: " + authorizationHeader
                    + " | error: " + exception.getMessage(), exception);
            throw BecknAuthException.internalError(ErrorMessages.INTERNAL_SERVER_ERROR, exception);
        }
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Releases background threads held by the cache implementation.
     * <p>
     * Must be called when the SDK instance is no longer needed to prevent
     * thread leaks in long-running applications or during hot reloads.
     * </p>
     */
    public void shutdown() {
        config.getCache().shutdown();
        logger.info("BecknAuth SDK shut down");
    }

    // ─── Private helpers ────────────────────────────────────────────────────────

    /**
     * Parses the private key at construction time (fail-fast).
     * Returns {@code null} if signing is not enabled (verification-only mode).
     */
    private PrivateKey initializePrivateKey(BecknAuthConfig config) {
        if (!config.isSigningEnabled()) {
            return null;
        }
        try {
            PrivateKey parsedPrivateKey = cryptoService.parsePrivateKey(config.getPrivateKey());
            logger.info("Private key loaded successfully");
            return parsedPrivateKey;
        } catch (BecknAuthException exception) {
            logger.error("[SIGNING] FAILED | cause: private key initialization | error: " + exception.getMessage());
            throw exception;
        } catch (Exception exception) {
            logger.error("[SIGNING] FAILED | cause: private key initialization | error: " + exception.getMessage(),
                    exception);
            throw BecknAuthException.internalError("Failed to initialize private key", exception);
        }
    }

    /**
     * Parses the Authorization header, validates algorithm, and validates timestamps.
     */
    private ParsedAuthHeader parseAndValidateHeader(String authorizationHeader, BecknContext ctx) {
        ParsedAuthHeader parsedHeader = headerParser.parseAuthorizationHeader(authorizationHeader);
        headerParser.validateAlgorithm(parsedHeader);
        headerParser.validateTimestamps(parsedHeader, config.getAllowedClockSkewSeconds());
        logger.info("[VERIFICATION] Header parsed"
                + " | txnId: " + ctx.transactionId()
                + " | msgId: " + ctx.messageId()
                + " | subscriber: " + parsedHeader.subscriberId()
                + " | keyId: " + parsedHeader.uniqueKeyId());
        return parsedHeader;
    }

    /**
     * Fetches the signer's Ed25519 public key from the registry (or cache).
     */
    private PublicKey fetchSignerPublicKey(ParsedAuthHeader parsedHeader, BecknContext ctx) {
        PublicKey signerPublicKey = registryService.getPublicKey(
                parsedHeader.subscriberId(), parsedHeader.uniqueKeyId());
        logger.info("[VERIFICATION] Public key resolved"
                + " | txnId: " + ctx.transactionId()
                + " | msgId: " + ctx.messageId()
                + " | subscriber: " + parsedHeader.subscriberId());
        return signerPublicKey;
    }

    /**
     * Reconstructs the signing string from the raw body and verifies the
     * Ed25519 signature in the parsed header.
     *
     * @throws BecknAuthException with {@code SEC_SIGNATURE_INVALID} (401) if
     *                            signature does not match
     */
    private void verifyBodySignature(ParsedAuthHeader parsedHeader, String rawRequestBody,
            PublicKey signerPublicKey, String authorizationHeader, BecknContext ctx) {
        String bodyDigest = cryptoService.generateBlake2bHash(rawRequestBody);
        String signingString = headerBuilder.buildSigningString(
                parsedHeader.created(), parsedHeader.expires(), bodyDigest);

        boolean isSignatureValid = cryptoService.verifyEd25519Signature(
                signingString, parsedHeader.signature(), signerPublicKey);

        if (!isSignatureValid) {
            logger.error("[VERIFICATION] FAILED"
                    + " | txnId: " + ctx.transactionId()
                    + " | msgId: " + ctx.messageId()
                    + " | cause: signature cryptographic mismatch"
                    + " | authHeader: " + authorizationHeader
                    + " | error: " + ErrorMessages.AUTH_VERIFICATION_FAILED);
            throw BecknAuthException.signatureVerificationFailed(
                    ErrorMessages.AUTH_VERIFICATION_FAILED, ErrorCodes.SEC_SIGNATURE_INVALID);
        }

        logger.info("[VERIFICATION] Signature verified"
                + " | txnId: " + ctx.transactionId()
                + " | msgId: " + ctx.messageId()
                + " | subscriber: " + parsedHeader.subscriberId());
    }

    /**
     * Extracts {@code transaction_id} and {@code message_id} from the
     * {@code context} object in the request body JSON for log correlation.
     * Returns {@code "unknown"} for both fields if parsing fails or the fields
     * are absent.
     */
    private BecknContext extractContext(String rawBody) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawBody);
            JsonNode ctx = root.path("context");
            String txnId = ctx.hasNonNull("transactionId") ? ctx.path("transactionId").asText("unknown")
                    : ctx.path("transaction_id").asText("unknown");
            String msgId = ctx.hasNonNull("messageId") ? ctx.path("messageId").asText("unknown")
                    : ctx.path("message_id").asText("unknown");
            return new BecknContext(txnId, msgId);
        } catch (Exception e) {
            return new BecknContext("unknown", "unknown");
        }
    }

    /** Holds the Beckn context fields extracted from a request body. */
    private record BecknContext(String transactionId, String messageId) {}
}
