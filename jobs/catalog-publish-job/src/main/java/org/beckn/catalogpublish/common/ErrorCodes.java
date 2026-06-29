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
    public static final String CTX_MISSING_FIELD = "CTX_MISSING_FIELD";
    public static final String CTX_INVALID_FIELD = "CTX_INVALID_FIELD";

    // ── Authentication / trust errors (canonical AUT_* per beckn.yaml ErrorCode enum) ──
    // The beckn-auth-java-sdk surfaces legacy SEC_* codes; these are the spec-compliant
    // values they translate to before reaching a client-facing NACK.
    public static final String AUT_SIGNATURE_MISSING = "AUT_SIGNATURE_MISSING";
    public static final String AUT_SIGNATURE_INVALID = "AUT_SIGNATURE_INVALID";
    public static final String AUT_SUBSCRIBER_NOT_FOUND = "AUT_SUBSCRIBER_NOT_FOUND";
    public static final String AUT_KEY_NOT_FOUND = "AUT_KEY_NOT_FOUND";
    public static final String AUT_KEY_EXPIRED_OR_REVOKED = "AUT_KEY_EXPIRED_OR_REVOKED";
    public static final String AUT_UNAUTHORIZED_ACTION = "AUT_UNAUTHORIZED_ACTION";

    // ── Network / infrastructure errors ───────────────────────────────────────
    public static final String NET_INTERNAL_ERROR = "NET_INTERNAL_ERROR";
    public static final String NET_OVERLOADED = "NET_OVERLOADED";
}
