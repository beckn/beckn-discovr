package org.beckn.auth.verification;

import java.security.PublicKey;

/**
 * Cached registry entry holding both the subscriber's public key and
 * canonical URL from a single DeDi registry lookup.
 * <p>
 * Stored as a single cache value keyed by {@code subscriberId|uniqueKeyId},
 * so future registry fields can be added without introducing new cache keys.
 * </p>
 *
 * @param publicKey     the Ed25519 public key for signature verification
 * @param subscriberUrl the canonical callback base URI ({@code details.url}),
 *                      or {@code null} if the field was absent
 */
public record RegistryEntry(PublicKey publicKey, String subscriberUrl) {
}
