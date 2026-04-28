package org.beckn.discover.common;

/**
 * User-facing error message constants for the catalog-discover-job.
 *
 * <p>All error messages returned to API consumers MUST be defined here as constants.
 * Messages must be actionable (tell the user what went wrong and what to do)
 * without leaking internal state, infrastructure details, or implementation specifics.</p>
 *
 * <p>Auth constants are aligned with the shared {@code beckn-auth-java-sdk}
 * {@link org.beckn.auth.util.ErrorMessages} and the Node.js {@code ErrorMessages.ts}.</p>
 */
public final class ErrorMessages {
    private ErrorMessages() {
    }

    // ── Authorization Errors ─────────────────────────────────────────────────
    public static final String AUTH_HEADER_MISSING = "Missing Authorization or X-Gateway-Authorization header";
    public static final String AUTH_INVALID_FORMAT = "Invalid Beckn HTTP Signature format";
    public static final String AUTH_FUTURE_CREATED =
            "Signature timestamp is in the future. Check your system clock and re-sign the request";
    public static final String AUTH_EXPIRED =
            "Signature has expired. Re-sign the request with a current timestamp";
    public static final String AUTH_VERIFICATION_FAILED =
            "Signature verification failed. Ensure you are signing with the correct private key registered in the Beckn registry";
    public static final String AUTH_PARTIAL_SIGNATURE =
            "Signature is incomplete. Ensure the Authorization header includes all required fields (keyId, algorithm, created, expires, headers, signature)";
    public static final String AUTH_SUBSCRIBER_NOT_FOUND =
            "Could not identify the subscriber. Ensure the Authorization header contains a valid keyId";

    // ── Registry / Key Errors ────────────────────────────────────────────────
    public static final String AUTH_PUBLIC_KEY_EXPIRED =
            "Your signing key is expired or revoked. Register a new key in the Beckn registry";
    public static final String REGISTRY_RECORD_NOT_FOUND =
            "Signing key not found. Ensure your key is registered in the Beckn registry";

    // ── Service State Errors ─────────────────────────────────────────────────
    public static final String SERVICE_STARTING_UP =
            "Service is starting up. Try again in a few seconds";
    public static final String SERVICE_MISCONFIGURED =
            "Service is misconfigured. Contact the service administrator";

    // ── Validation Errors ────────────────────────────────────────────────────
    public static final String VALIDATION_FAILED =
            "Request validation failed";
    public static final String INVALID_UUID =
            "must be a valid UUID";
    public static final String SCHEMA_CONFIG_ERROR =
            "Schema configuration error. Contact the service administrator";

    // ── Generic Errors ───────────────────────────────────────────────────────
    public static final String INTERNAL_SERVER_ERROR = "Internal server error occurred";
}

