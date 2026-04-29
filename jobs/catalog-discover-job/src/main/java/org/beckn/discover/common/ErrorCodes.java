package org.beckn.discover.common;

/**
 * Beckn Protocol v2.0 error codes.
 *
 * <p>Naming convention: {@code CATEGORY_SPECIFIC_DETAIL}.</p>
 * <ul>
 *   <li>{@code SCH_*} — schema / request validation errors</li>
 *   <li>{@code CTX_*} — context field errors</li>
 *   <li>{@code NET_*} — network / infrastructure errors</li>
 *   <li>{@code SEC_*} — security &amp; authorization errors (aligned with beckn-auth-java-sdk)</li>
 * </ul>
 *
 * <p>Every constant here has a matching constant with the same name in {@link ErrorMessages}.</p>
 */
public final class ErrorCodes {
    private ErrorCodes() {
    }

    // ── Schema / Validation Errors ──────────────────────────────────────────
    public static final String SCH_INVALID_JSON = "SCH_INVALID_JSON";
    public static final String SCH_SCHEMA_VALIDATION_FAILED = "SCH_SCHEMA_VALIDATION_FAILED";
    public static final String SCH_REQUIRED_FIELD_MISSING = "SCH_REQUIRED_FIELD_MISSING";

    // ── Context Field Errors ────────────────────────────────────────────────
    public static final String CTX_INVALID_FIELD = "CTX_INVALID_FIELD";

    // ── Network / Infrastructure Errors ─────────────────────────────────────
    public static final String NET_INTERNAL_ERROR = "NET_INTERNAL_ERROR";
    public static final String NET_SERVICE_UNAVAILABLE = "NET_SERVICE_UNAVAILABLE";

    // ── Security & Authorization Errors (aligned with beckn-auth-java-sdk) ──
    public static final String SEC_SIGNATURE_MISSING = "SEC_SIGNATURE_MISSING";
    public static final String SEC_SIGNATURE_INVALID = "SEC_SIGNATURE_INVALID";
    public static final String SEC_SUBSCRIBER_NOT_FOUND = "SEC_SUBSCRIBER_NOT_FOUND";
    public static final String SEC_KEY_NOT_FOUND = "SEC_KEY_NOT_FOUND";
    public static final String SEC_KEY_EXPIRED_OR_REVOKED = "SEC_KEY_EXPIRED_OR_REVOKED";
    public static final String SEC_UNAUTHORIZED_ACTION = "SEC_UNAUTHORIZED_ACTION";
}
