package org.beckn.catalogpublish.common;

/**
 * Beckn Protocol v2 error codes.
 * <p>Every constant here MUST have a matching constant with the same name in {@link ErrorMessages}.
 */
public final class ErrorCodes {
    private ErrorCodes() {}

    // ── Schema / payload errors ───────────────────────────────────────────────
    public static final String SCH_INVALID_JSON = "SCH_INVALID_JSON";
    public static final String SCH_SCHEMA_VALIDATION_FAILED = "SCH_SCHEMA_VALIDATION_FAILED";
    public static final String SCH_REQUIRED_FIELD_MISSING = "SCH_REQUIRED_FIELD_MISSING";

    // ── Context errors ────────────────────────────────────────────────────────
    public static final String CTX_INVALID_FIELD = "CTX_INVALID_FIELD";

    // ── Network / infrastructure errors ───────────────────────────────────────
    public static final String NET_INTERNAL_ERROR = "NET_INTERNAL_ERROR";
    public static final String NET_OVERLOADED = "NET_OVERLOADED";

    // ── Request-level errors ──────────────────────────────────────────────────
    public static final String REQUEST_TOO_LARGE = "REQUEST_TOO_LARGE";
}
