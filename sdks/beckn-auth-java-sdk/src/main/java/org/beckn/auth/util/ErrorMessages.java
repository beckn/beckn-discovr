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
    public static final String AUTH_HEADER_MISSING = "Authorization header is missing. Include an Authorization header with your request";

    /** Header does not conform to the Beckn HTTP Signature specification. */
    public static final String AUTH_INVALID_FORMAT = "Authorization header format is invalid. Refer to the API documentation for the correct format";

    /** The 'created' timestamp in the signature is in the future. */
    public static final String AUTH_FUTURE_CREATED =
            "Request timestamp is in the future. Check that your system clock is accurate and try again";

    /** The 'expires' timestamp in the signature has passed. */
    public static final String AUTH_EXPIRED =
            "Authorization has expired. Generate fresh credentials and retry the request";

    /** Cryptographic signature verification against the public key failed. */
    public static final String AUTH_VERIFICATION_FAILED =
            "Authorization verification failed. Ensure your credentials are correct and your account is active";

    /** One or more required fields are missing from the Signature header. */
    public static final String AUTH_PARTIAL_SIGNATURE =
            "Authorization header is incomplete. Ensure all required fields are present. Refer to the API documentation for the correct format";

    /** Subscriber ID portion of the keyId is empty or missing. */
    public static final String AUTH_SUBSCRIBER_NOT_FOUND =
            "Could not identify the requester. Ensure your Authorization header is correct and your account is registered";

    /** Public key retrieved from registry is expired or revoked. */
    public static final String AUTH_PUBLIC_KEY_EXPIRED =
            "Your credentials have expired or been revoked. Contact your administrator to renew access";

    /** No matching public key record found in the Beckn registry. */
    public static final String REGISTRY_RECORD_NOT_FOUND =
            "Credentials not found. Ensure your account is registered and active";

    /** Registry returned an empty or null response body. */
    public static final String REGISTRY_EMPTY_RESPONSE =
            "Could not verify your credentials. The verification service is temporarily unavailable — try again later";

    /** Private key was not configured but is required for signature generation. */
    public static final String PRIVATE_KEY_NOT_CONFIGURED = "Service configuration error. Contact the service administrator";

    /** A generic internal server error occurred. */
    public static final String INTERNAL_SERVER_ERROR = "An unexpected server error occurred. Try again later or contact support if the issue persists";

    /** Registry could not be reached after exhausting all retry attempts. */
    public static final String REGISTRY_CONNECTION_ERROR =
            "Could not verify your credentials. The verification service is temporarily unavailable — try again later";

    /** Subscriber is not authorized to perform the requested action. */
    public static final String AUTH_UNAUTHORIZED_ACTION =
            "You are not authorized to perform this action. Contact your administrator for access";

    /** Generic fallback for unmapped auth errors. */
    public static final String AUTH_FALLBACK =
            "Authentication failed. Check your Authorization header and try again";
}
