package org.beckn.discover.logging;

/**
 * Constants for internal log message values and reasons.
 *
 * <p>
 * Unlike ErrorMessages (which are shown to API clients), these strings
 * are strictly for internal debugging and log aggregation.
 */
public final class LogMessages {

    private LogMessages() {
        // Utility class
    }

    // ── Validation ───────────────────────────────────────────────────────────
    public static final String REASON_SCHEMA_NOT_INITIALIZED = "schema-not-initialized";
    public static final String REASON_UNEXPECTED_ERROR = "unexpected-error";
    public static final String REASON_NULL_REQUEST = "null-request";

    // ── Consumer / Publishing ────────────────────────────────────────────────
    public static final String REASON_RESPONSE_TOPIC_NOT_CONFIGURED = "response-topic-not-configured";
    public static final String REASON_PUBLISH_TIMED_OUT = "publish timed out after 30s";

    // ── Query Engine ─────────────────────────────────────────────────────────
    public static final String REASON_NO_SPATIAL_CONDITIONS = "no-spatial-conditions";
}
