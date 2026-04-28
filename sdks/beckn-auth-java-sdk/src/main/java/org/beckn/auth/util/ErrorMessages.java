package org.beckn.auth.util;

/**
 * Human-readable error message constants.
 *
 * <p>Each message corresponds to a specific failure scenario during
 * signature generation or verification.</p>
 *
 * <p>All messages must be actionable (tell the user what went wrong and what to do)
 * without leaking internal state, infrastructure details, or implementation specifics.</p>
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
    public static final String AUTH_FUTURE_CREATED =
            "Signature timestamp is in the future. Check your system clock and re-sign the request";

    /** The 'expires' timestamp in the signature has passed. */
    public static final String AUTH_EXPIRED =
            "Signature has expired. Re-sign the request with a current timestamp";

    /** Cryptographic signature verification against the public key failed. */
    public static final String AUTH_VERIFICATION_FAILED =
            "Signature verification failed. Ensure you are signing with the correct private key registered in the Beckn registry";

    /** One or more required fields are missing from the Signature header. */
    public static final String AUTH_PARTIAL_SIGNATURE =
            "Signature is incomplete. Ensure the Authorization header includes all required fields (keyId, algorithm, created, expires, headers, signature)";

    /** Subscriber ID portion of the keyId is empty or missing. */
    public static final String AUTH_SUBSCRIBER_NOT_FOUND =
            "Could not identify the subscriber. Ensure the Authorization header contains a valid keyId";

    /** Public key retrieved from registry is expired or revoked. */
    public static final String AUTH_PUBLIC_KEY_EXPIRED =
            "Your signing key is expired or revoked. Register a new key in the Beckn registry";

    /** No matching public key record found in the Beckn registry. */
    public static final String REGISTRY_RECORD_NOT_FOUND =
            "Signing key not found. Ensure your key is registered in the Beckn registry";

    /** Registry returned an empty or null response body. */
    public static final String REGISTRY_EMPTY_RESPONSE =
            "Could not verify your identity. The registry is temporarily unavailable — try again later";

    /** Private key was not configured but is required for signature generation. */
    public static final String PRIVATE_KEY_NOT_CONFIGURED = "Private key not configured";

    /** A generic internal server error occurred. */
    public static final String INTERNAL_SERVER_ERROR = "Internal server error occurred";

    /** Registry could not be reached after exhausting all retry attempts. */
    public static final String REGISTRY_CONNECTION_ERROR =
            "Could not verify your identity. The registry is temporarily unavailable — try again later";

    /** Generic fallback for unmapped auth errors. */
    public static final String AUTH_FALLBACK =
            "Authentication failed. Check your Authorization header and try again";
}

