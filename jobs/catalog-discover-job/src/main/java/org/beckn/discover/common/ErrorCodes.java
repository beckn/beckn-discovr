package org.beckn.discover.common;

/**
 * Error codes for Authorization Service.
 * Aligned with Node.js ErrorCodes.ts (Auth subset)
 */
public final class ErrorCodes {
    private ErrorCodes() {
    }

    // Security & Authorization Errors
    public static final String SEC_SIGNATURE_MISSING = "SEC_SIGNATURE_MISSING";
    public static final String SEC_SIGNATURE_INVALID = "SEC_SIGNATURE_INVALID";
    public static final String SEC_SUBSCRIBER_NOT_FOUND = "SEC_SUBSCRIBER_NOT_FOUND";
    public static final String SEC_KEY_NOT_FOUND = "SEC_KEY_NOT_FOUND";
    public static final String SEC_KEY_EXPIRED_OR_REVOKED = "SEC_KEY_EXPIRED_OR_REVOKED";
    public static final String SEC_UNAUTHORIZED_ACTION = "SEC_UNAUTHORIZED_ACTION";

    // Generic/Network Errors (Used in Auth context)
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String NET_INTERNAL_ERROR = "NET_INTERNAL_ERROR";
}
