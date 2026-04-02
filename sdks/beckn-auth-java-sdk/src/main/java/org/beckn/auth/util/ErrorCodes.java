package org.beckn.auth.util;

/**
 * Security and application error code constants.
 * Mirrors the error codes used in discovery-service-v2 for consistent error
 * handling
 * across the Beckn ecosystem.
 */
public final class ErrorCodes {

    private ErrorCodes() {
        // Utility class, no instantiation
    }

    /** Authorization header is completely missing from the request. */
    public static final String SEC_SIGNATURE_MISSING = "SEC_SIGNATURE_MISSING";

    /** Signature format is malformed or cryptographic verification failed. */
    public static final String SEC_SIGNATURE_INVALID = "SEC_SIGNATURE_INVALID";

    /** Subscriber ID extracted from keyId does not exist or is empty. */
    public static final String SEC_SUBSCRIBER_NOT_FOUND = "SEC_SUBSCRIBER_NOT_FOUND";

    /** Public key for the given subscriber/keyId was not found in the registry. */
    public static final String SEC_KEY_NOT_FOUND = "SEC_KEY_NOT_FOUND";

    /** Public key exists but is expired, revoked, or not in 'live' state. */
    public static final String SEC_KEY_EXPIRED_OR_REVOKED = "SEC_KEY_EXPIRED_OR_REVOKED";

    /** Subscriber is not authorized to perform the requested action. */
    public static final String SEC_UNAUTHORIZED_ACTION = "SEC_UNAUTHORIZED_ACTION";

    /** Generic invalid request (e.g. malformed JSON body). */
    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    /** An unexpected internal error occurred within the SDK. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /** Network-level internal error (e.g. registry unreachable after retries). */
    public static final String NET_INTERNAL_ERROR = "NET_INTERNAL_ERROR";
}
