package org.beckn.auth.logging;

/**
 * Pluggable logging interface for the Beckn Auth SDK.
 * <p>
 * Use {@link LoggerFactory#createLogger(Class)} to obtain the best available
 * implementation at runtime.
 * </p>
 * <p>
 * <b>ERROR-level contract:</b> implementations MUST log the full message,
 * which will include the auth header, transaction ID, and subscriber ID
 * when available. This is critical for diagnosing auth failures in production.
 * </p>
 */
public interface Logger {

    /**
     * Trace-level detail: signing string construction, cache lookups, key parsing steps.
     *
     * @param message the debug message
     */
    void debug(String message);

    /**
     * Lifecycle events: key loaded successfully, cache hit/miss, verification success.
     *
     * @param message the info message
     */
    void info(String message);

    /**
     * Non-fatal issues: retry attempts, cache eviction, key state warnings.
     *
     * @param message the warning message
     */
    void warn(String message);

    /**
     * Failures with a root cause throwable. Includes auth header, transaction ID,
     * and subscriber ID context where available.
     *
     * @param message the error message
     * @param cause   the underlying exception
     */
    void error(String message, Throwable cause);

    /**
     * Failures without a throwable (e.g. signature mismatch, timestamp expired).
     *
     * @param message the error message
     */
    void error(String message);
}
