package org.beckn.discover.common;

/**
 * User-facing error message constants for the catalog-discover-job.
 *
 * <p>All error messages returned to API consumers MUST be defined here as constants.
 * Messages must be actionable (tell the user what went wrong and what to do)
 * without leaking internal state, infrastructure details, or implementation specifics.</p>
 *
 * <p><b>Naming convention:</b> Every constant name matches its counterpart in {@link ErrorCodes}.
 * For example, {@code ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED} pairs with
 * {@code ErrorMessages.SCH_SCHEMA_VALIDATION_FAILED}.</p>
 *
 * <p>Auth constants are aligned with the shared {@code beckn-auth-java-sdk}
 * {@link org.beckn.auth.util.ErrorMessages} and the Node.js {@code ErrorMessages.ts}.</p>
 */
public final class ErrorMessages {
    private ErrorMessages() {
    }

    // ── Schema / Validation Errors (pair with ErrorCodes.SCH_*) ─────────────
    public static final String SCH_INVALID_JSON = "Request body is not valid JSON. Verify the JSON syntax and try again";
    public static final String SCH_SCHEMA_VALIDATION_FAILED =
            "Request validation failed. Check your request body against the Beckn schema";
    public static final String SCH_REQUIRED_FIELD_MISSING = "A required field is missing in the request. Check the API documentation for required fields";
    public static final String SCH_INVALID_JSONPATH = "The JSONPath filter expression is invalid. Verify the expression syntax and try again";

    // ── Context Field Errors (pair with ErrorCodes.CTX_*) ───────────────────
    public static final String CTX_INVALID_FIELD = "A field in the request has an invalid value. Check the field format and allowed values";

    // ── Network / Infrastructure Errors (pair with ErrorCodes.NET_*) ────────
    public static final String NET_INTERNAL_ERROR = "An unexpected server error occurred. Try again later or contact support if the issue persists";
    public static final String NET_DOWNSTREAM_UNAVAILABLE =
            "Service is starting up. Try again in a few seconds";
    public static final String NET_SEARCH_SERVICE_UNAVAILABLE =
            "Search service is temporarily unavailable. Try again later";

    // ── Authentication Errors (user-facing messages for the AUT_* codes) ──────
    public static final String AUTH_HEADER_MISSING = "Authorization header is missing. Include an Authorization header with your request";
    public static final String AUTH_INVALID_FORMAT = "Authorization header format is invalid. Refer to the API documentation for the correct format";
    public static final String AUTH_FUTURE_CREATED =
            "Request timestamp is in the future. Check that your system clock is accurate and try again";
    public static final String AUTH_EXPIRED =
            "Authorization has expired. Generate fresh credentials and retry the request";
    public static final String AUTH_VERIFICATION_FAILED =
            "Authorization verification failed. Ensure your credentials are correct and your account is active";
    public static final String AUTH_PARTIAL_SIGNATURE =
            "Authorization header is incomplete. Ensure all required fields are present. Refer to the API documentation for the correct format";
    public static final String AUTH_SUBSCRIBER_NOT_FOUND =
            "Could not identify the requester. Ensure your Authorization header is correct and your account is registered";

    // ── Registry / Key Errors ────────────────────────────────────────────────
    public static final String AUTH_PUBLIC_KEY_EXPIRED =
            "Your credentials have expired or been revoked. Contact your administrator to renew access";
    public static final String REGISTRY_RECORD_NOT_FOUND =
            "Credentials not found. Ensure your account is registered and active";

    // ── AUT_* message aliases (1:1 match with ErrorCodes.AUT_*) ──────────────
    public static final String AUT_SIGNATURE_MISSING = AUTH_HEADER_MISSING;
    public static final String AUT_SIGNATURE_INVALID = AUTH_VERIFICATION_FAILED;
    public static final String AUT_SUBSCRIBER_NOT_FOUND = AUTH_SUBSCRIBER_NOT_FOUND;
    public static final String AUT_KEY_NOT_FOUND = REGISTRY_RECORD_NOT_FOUND;
    public static final String AUT_KEY_EXPIRED_OR_REVOKED = AUTH_PUBLIC_KEY_EXPIRED;
    public static final String AUT_UNAUTHORIZED_ACTION = "You are not authorized to perform this action. Contact your administrator for access";
}

