package org.beckn.discover.common;

/**
 * Error messages for Authorization Service.
 * Aligned with Node.js ErrorMessages.ts (Auth subset)
 */
public final class ErrorMessages {
    private ErrorMessages() {
    }

    // Authorization Errors
    public static final String AUTH_HEADER_MISSING = "Missing Authorization or X-Gateway-Authorization header";
    public static final String AUTH_INVALID_FORMAT = "Invalid Beckn HTTP Signature format";
    public static final String AUTH_FUTURE_CREATED = "Signature created in the future";
    public static final String AUTH_EXPIRED = "Signature has expired";
    public static final String AUTH_VERIFICATION_FAILED = "Signature verification failed";
    public static final String AUTH_PARTIAL_SIGNATURE = "Signature incomplete";
    public static final String AUTH_SUBSCRIBER_NOT_FOUND = "Subscriber ID missing in keyId";

    // Registry/Key Errors
    public static final String AUTH_PUBLIC_KEY_EXPIRED = "Key is expired or revoked";
    public static final String REGISTRY_RECORD_NOT_FOUND = "Public key not found in registry";

    // Generic
    public static final String INTERNAL_SERVER_ERROR = "Internal server error occurred";
}
