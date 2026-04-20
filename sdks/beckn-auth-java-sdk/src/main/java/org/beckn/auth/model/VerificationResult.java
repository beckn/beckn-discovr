package org.beckn.auth.model;

/**
 * Result of {@link org.beckn.auth.BecknAuth#verifySignature}.
 *
 * @param parsedHeader   the parsed Authorization header (subscriberId, uniqueKeyId, etc.)
 * @param subscriberUrl  the canonical callback base URI from the DeDi registry
 *                       ({@code details.url}), or {@code null} if the field was
 *                       absent in the registry response
 */
public record VerificationResult(
        ParsedAuthHeader parsedHeader,
        String subscriberUrl) {
}
