package org.beckn.auth.util;

/**
 * Human-readable error message constants.
 * Each message corresponds to a specific failure scenario during
 * signature generation or verification.
 */
public final class ErrorMessages {

    private ErrorMessages() {
        // Utility class, no instantiation
    }

    /** Authorization/X-Gateway-Authorization header is absent. */
    public static final String AUTH_HEADER_MISSING = "Missing Authorization or X-Gateway-Authorization header";

    /** Header does not conform to the Beckn HTTP Signature specification. */
    public static final String AUTH_INVALID_FORMAT = "Invalid Beckn HTTP Signature format";

    /** The 'created' timestamp in the signature is in the future. */
    public static final String AUTH_FUTURE_CREATED = "Signature created in the future";

    /** The 'expires' timestamp in the signature has passed. */
    public static final String AUTH_EXPIRED = "Signature has expired";

    /** Cryptographic signature verification against the public key failed. */
    public static final String AUTH_VERIFICATION_FAILED = "Signature verification failed";

    /** One or more required fields are missing from the Signature header. */
    public static final String AUTH_PARTIAL_SIGNATURE = "Signature incomplete";

    /** Subscriber ID portion of the keyId is empty or missing. */
    public static final String AUTH_SUBSCRIBER_NOT_FOUND = "Subscriber ID missing in keyId";

    /** Public key retrieved from registry is expired or revoked. */
    public static final String AUTH_PUBLIC_KEY_EXPIRED = "Key is expired or revoked";

    /** No matching public key record found in the Beckn registry. */
    public static final String REGISTRY_RECORD_NOT_FOUND = "Public key not found in registry";

    /** Registry returned an empty or null response body. */
    public static final String REGISTRY_EMPTY_RESPONSE = "Registry returned empty response";

    /** Private key was not configured but is required for signature generation. */
    public static final String PRIVATE_KEY_NOT_CONFIGURED = "Private key not configured";

    /** A generic internal server error occurred. */
    public static final String INTERNAL_SERVER_ERROR = "Internal server error occurred";

    /** Registry could not be reached after exhausting all retry attempts. */
    public static final String REGISTRY_CONNECTION_ERROR = "Registry connection failed after retries";
}
