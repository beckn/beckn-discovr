package org.beckn.auth.signing;

import org.beckn.auth.logging.Logger;

/**
 * Builds signing strings and Authorization headers per the Beckn HTTP
 * Signature specification (SigningDOC.md).
 *
 * <h3>Signing String Format</h3>
 * <pre>
 * (created): {unix_epoch_seconds}
 * (expires): {unix_epoch_seconds}
 * digest: BLAKE-512={base64_encoded_hash}
 * </pre>
 *
 * <h3>Authorization Header Format</h3>
 * <pre>
 * Signature keyId="{subscriberId}|{uniqueKeyId}|ed25519",algorithm="ed25519",
 *           created="{ts}",expires="{ts}",
 *           headers="(created) (expires) digest",
 *           signature="{base64_encoded_ed25519_signature}"
 * </pre>
 *
 * <p>
 * This class is stateless and thread-safe. A single instance can be shared
 * across all request-handling threads.
 * </p>
 */
public final class SignatureHeaderBuilder {

    private static final String ALGORITHM = "ed25519";

    private final Logger logger;

    /**
     * Constructs a SignatureHeaderBuilder with the given logger.
     *
     * @param logger the pluggable logger for debug output
     */
    public SignatureHeaderBuilder(Logger logger) {
        this.logger = logger;
    }

    /**
     * Builds the signing string from timestamp and digest components.
     * <p>
     * The resulting string is the exact input to the Ed25519 signing operation
     * and must be reconstructed identically on the receiver side for verification.
     * </p>
     *
     * @param created Unix epoch second when the signature is created
     * @param expires Unix epoch second when the signature expires
     * @param digest  Base64-encoded BLAKE2b-512 hash of the raw request body
     * @return the formatted signing string, e.g.:
     *         {@code "(created): 1641287875\n(expires): 1641291475\ndigest: BLAKE-512=..."}
     */
    public String buildSigningString(long created, long expires, String digest) {
        String signingString = "(created): " + created
                + "\n(expires): " + expires
                + "\ndigest: BLAKE-512=" + digest;
        logger.debug("Signing string built | created=" + created + " | expires=" + expires);
        return signingString;
    }

    /**
     * Builds the complete {@code Authorization} (or {@code X-Gateway-Authorization})
     * header value per the Beckn HTTP Signature specification.
     * <p>
     * Format strictly matches SigningDOC.md — no spaces between comma-separated params.
     * </p>
     *
     * @param subscriberId the subscriber ID (bap_id / bpp_id / bg_id)
     * @param uniqueKeyId  the unique key ID registered in the Beckn registry
     * @param created      Unix epoch second when the signature was created
     * @param expires      Unix epoch second when the signature expires
     * @param signature    Base64-encoded Ed25519 signature of the signing string
     * @return the complete Signature header value, e.g.:
     *         {@code Signature keyId="example-bap.com|key-uuid|ed25519",algorithm="ed25519",...}
     */
    public String buildAuthorizationHeader(String subscriberId, String uniqueKeyId,
            long created, long expires, String signature) {
        String keyId = subscriberId + "|" + uniqueKeyId + "|" + ALGORITHM;
        String header = "Signature keyId=\"" + keyId
                + "\",algorithm=\"" + ALGORITHM
                + "\",created=\"" + created
                + "\",expires=\"" + expires
                + "\",headers=\"(created) (expires) digest"
                + "\",signature=\"" + signature + "\"";
        logger.debug("Authorization header built | keyId=" + keyId);
        return header;
    }
}
