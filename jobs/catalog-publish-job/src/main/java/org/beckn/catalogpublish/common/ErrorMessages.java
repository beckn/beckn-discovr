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
    public static final String CTX_INVALID_FIELD = "A field in the request has an invalid value. Check the field format and allowed values";

    // ── Network / infrastructure errors ───────────────────────────────────────
    public static final String NET_INTERNAL_ERROR = "An unexpected server error occurred. Try again later or contact support if the issue persists";
    public static final String NET_OVERLOADED = "Service is temporarily overloaded. Try again in a few moments";

    // ── Request-level errors ──────────────────────────────────────────────────
    public static final String REQUEST_TOO_LARGE = "Request body exceeds the maximum allowed size. Reduce the payload and try again";
}
