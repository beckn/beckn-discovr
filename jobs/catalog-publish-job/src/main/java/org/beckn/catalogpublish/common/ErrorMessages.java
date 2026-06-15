package org.beckn.catalogpublish.common;

/**
 * Human-readable error messages matching {@link ErrorCodes}.
 * <p>Every constant name MUST match a constant in {@link ErrorCodes} (1:1 naming convention).
 */
public final class ErrorMessages {
    private ErrorMessages() {}

    // ── Schema / payload errors ───────────────────────────────────────────────
    public static final String SCH_INVALID_JSON = "Request body is not valid JSON. Verify the JSON syntax and try again";
    public static final String SCH_SCHEMA_VALIDATION_FAILED =
            "Request validation failed. Check your request body against the Beckn schema";
    public static final String SCH_REQUIRED_FIELD_MISSING = "A required field is missing in the request. Check the API documentation for required fields";
    public static final String SCH_MISSING_CONTEXT =
            "Request must include a context object. Add context with at least messageId or transactionId";

    // ── Context errors ────────────────────────────────────────────────────────
    public static final String CTX_MISSING_FIELD =
            "A required context field is missing. Include both messageId and transactionId in the request context";
    public static final String CTX_INVALID_FIELD = "A field in the request has an invalid value. Check the field format and allowed values";

    // ── Authentication / trust errors (pair with ErrorCodes.AUT_*) ─────────────
    public static final String AUT_SIGNATURE_MISSING = "Authorization signature is missing. Sign the request with your Beckn HTTP signature";
    public static final String AUT_SIGNATURE_INVALID = "Authorization signature is invalid or could not be verified";
    public static final String AUT_SUBSCRIBER_NOT_FOUND = "The signing subscriber could not be found in the registry";
    public static final String AUT_KEY_NOT_FOUND = "The signing key could not be found in the registry";
    public static final String AUT_KEY_EXPIRED_OR_REVOKED = "The signing key has expired or been revoked";
    public static final String AUT_UNAUTHORIZED_ACTION = "The subscriber is not authorized to perform this action";

    // ── Network / infrastructure errors ───────────────────────────────────────
    public static final String NET_INTERNAL_ERROR = "An unexpected server error occurred. Try again later or contact support if the issue persists";
    public static final String NET_OVERLOADED = "Service is temporarily overloaded. Try again in a few moments";

    // ── Request-level messages ────────────────────────────────────────────────
    public static final String REQUEST_TOO_LARGE = "Request body exceeds the maximum allowed size. Reduce the payload and try again";
}
