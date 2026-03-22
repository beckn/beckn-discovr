package org.beckn.auth.model;

/**
 * Immutable record representing a parsed Beckn HTTP Signature Authorization
 * header.
 * <p>
 * The keyId in a Beckn authorization header follows the format:
 * {@code subscriberId|uniqueKeyId|algorithm}
 * </p>
 *
 * @param keyId        the full keyId string from the header
 * @param subscriberId the subscriber identifier (first segment of keyId)
 * @param uniqueKeyId  the unique key identifier (second segment of keyId)
 * @param algorithm    the algorithm name (third segment of keyId, must be
 *                     "ed25519")
 * @param created      the Unix epoch second when the signature was created
 * @param expires      the Unix epoch second when the signature expires
 * @param headers      the headers parameter value (e.g. "(created) (expires)
 *                     digest")
 * @param signature    the Base64-encoded Ed25519 signature
 */
public record ParsedAuthHeader(
        String keyId,
        String subscriberId,
        String uniqueKeyId,
        String algorithm,
        long created,
        long expires,
        String headers,
        String signature) {
}
