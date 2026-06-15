package org.beckn.discover.common;

/**
 * Beckn Protocol v2.0 error codes.
 *
 * <p>Naming convention: {@code CATEGORY_SPECIFIC_DETAIL}.</p>
 * <ul>
 *   <li>{@code SCH_*} — schema / request validation errors</li>
 *   <li>{@code CTX_*} — context field errors</li>
 *   <li>{@code NET_*} — network / infrastructure errors</li>
 *   <li>{@code AUT_*} — authentication / trust errors (Beckn v2.0 {@code ErrorCode} enum in beckn.yaml)</li>
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
    public static final String NET_DOWNSTREAM_UNAVAILABLE = "NET_DOWNSTREAM_UNAVAILABLE";

    // ── Authentication / Trust Errors (Beckn v2.0 ErrorCode enum, beckn.yaml) ──
    // Canonical AUT_* codes per the spec's ErrorCode enum. The beckn-auth-java-sdk
    // still emits its legacy SEC_* codes; AuthorizationService translates SEC_* → AUT_*
    // at the boundary so client-facing NACK responses carry spec-compliant codes.
    public static final String AUT_SIGNATURE_MISSING = "AUT_SIGNATURE_MISSING";
    public static final String AUT_SIGNATURE_INVALID = "AUT_SIGNATURE_INVALID";
    public static final String AUT_SUBSCRIBER_NOT_FOUND = "AUT_SUBSCRIBER_NOT_FOUND";
    public static final String AUT_KEY_NOT_FOUND = "AUT_KEY_NOT_FOUND";
    public static final String AUT_KEY_EXPIRED_OR_REVOKED = "AUT_KEY_EXPIRED_OR_REVOKED";
    public static final String AUT_UNAUTHORIZED_ACTION = "AUT_UNAUTHORIZED_ACTION";
}
