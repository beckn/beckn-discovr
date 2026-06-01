package org.beckn.auth.exception;

/**
 * Base exception for all Beckn authentication failures.
 * <p>
 * Mirrors the discovery-service-v2
 * {@code ErrorResponseException + ProblemDetail} pattern.
 * Carries all fields needed to build an {@code AckResponse.nack()} without any
 * framework dependency.
 * </p>
 *
 * <p>
 * Every instance carries appropriate {@code code}, {@code paths}, and
 * {@code httpStatus} so that callers can directly map exceptions to HTTP error
 * responses.
 * </p>
 */
public class BecknAuthException extends RuntimeException {

    private final String code;
    private final String paths;
    private final int httpStatus;

    /**
     * Constructs a new BecknAuthException.
     *
     * @param message    human-readable error description
     * @param code       error code constant from
     *                   {@link org.beckn.auth.util.ErrorCodes}
     * @param paths      dot-notation path indicating the failing field (e.g.
     *                   "authorization/keyId")
     * @param httpStatus the HTTP status code to return (e.g. 400, 401, 500)
     */
    public BecknAuthException(String message, String code, String paths, int httpStatus) {
        super(message);
        this.code = code;
        this.paths = paths;
        this.httpStatus = httpStatus;
    }

    /**
     * Constructs a new BecknAuthException with a root cause.
     *
     * @param message    human-readable error description
     * @param code       error code constant from
     *                   {@link org.beckn.auth.util.ErrorCodes}
     * @param paths      dot-notation path indicating the failing field
     * @param httpStatus the HTTP status code to return
     * @param cause      the underlying exception
     */
    public BecknAuthException(String message, String code, String paths, int httpStatus, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.paths = paths;
        this.httpStatus = httpStatus;
    }

    /** @return the error code constant (e.g. {@code SEC_SIGNATURE_INVALID}) */
    public String getCode() {
        return code;
    }

    /** @return the failing path (e.g. {@code "authorization/keyId"}) */
    public String getPaths() {
        return paths;
    }

    /** @return the HTTP status code (e.g. 400, 401, 500) */
    public int getHttpStatus() {
        return httpStatus;
    }

    // --- Static Factory Methods to replace subclass sprawl ---

    public static BecknAuthException internalError(String message) {
        return new BecknAuthException(message, org.beckn.auth.util.ErrorCodes.INTERNAL_ERROR, "", 500);
    }

    public static BecknAuthException internalError(String message, Throwable cause) {
        return new BecknAuthException(message, org.beckn.auth.util.ErrorCodes.INTERNAL_ERROR, "", 500, cause);
    }

    public static BecknAuthException invalidHeader(String message, String code) {
        return new BecknAuthException(message, code, "authorization", 400);
    }

    public static BecknAuthException invalidHeader(String message, String code, String paths) {
        return new BecknAuthException(message, code, paths, 400);
    }

    /**
     * Factory for authentication failures where the request header is missing or
     * syntactically invalid. Returns HTTP 400 (Bad Request) because the issue is
     * a malformed or absent header, not a failed authentication lookup.
     *
     * @param message human-readable error description
     * @param code    error code constant (e.g. SEC_SIGNATURE_MISSING, SEC_SUBSCRIBER_NOT_FOUND)
     * @param paths   dot-notation path indicating the failing field
     * @return a BecknAuthException with HTTP 400
     */
    public static BecknAuthException authenticationRequired(String message, String code, String paths) {
        return new BecknAuthException(message, code, paths, 400);
    }

    public static BecknAuthException signatureGenerationFailed(String message, String code) {
        return new BecknAuthException(message, code, "", 500);
    }

    public static BecknAuthException signatureGenerationFailed(String message, Throwable cause) {
        return new BecknAuthException(message, org.beckn.auth.util.ErrorCodes.INTERNAL_ERROR, "", 500, cause);
    }

    public static BecknAuthException signatureVerificationFailed(String message, String code) {
        return new BecknAuthException(message, code, "", 401);
    }

    public static BecknAuthException timestampExpired(String message, String paths) {
        return new BecknAuthException(message, org.beckn.auth.util.ErrorCodes.SEC_SIGNATURE_INVALID, paths, 401);
    }

    public static BecknAuthException registryError(String message, Throwable cause) {
        return new BecknAuthException(message, org.beckn.auth.util.ErrorCodes.NET_INTERNAL_ERROR, "", 502, cause);
    }

    public static BecknAuthException keyNotFound(String message) {
        return new BecknAuthException(message, org.beckn.auth.util.ErrorCodes.SEC_KEY_NOT_FOUND,
                "authorization/keyId", 401);
    }

    public static BecknAuthException keyExpired(String message) {
        return new BecknAuthException(message, org.beckn.auth.util.ErrorCodes.SEC_KEY_EXPIRED_OR_REVOKED,
                "authorization/keyId", 401);
    }
}
