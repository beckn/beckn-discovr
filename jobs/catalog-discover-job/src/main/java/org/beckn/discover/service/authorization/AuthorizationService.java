package org.beckn.discover.service.authorization;

import org.beckn.auth.BecknAuth;
import org.beckn.auth.exception.BecknAuthException;
import org.beckn.discover.common.ErrorCodes;
import org.beckn.discover.config.AuthProperties;
import org.beckn.discover.logging.BecknMdcContext;
import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Authorization Service — delegates Beckn HTTP Signature validation to the SDK.
 */
@Service
public class AuthorizationService {

    /**
     * Caller identity returned from a successful authorization check.
     * Carries the values on the calling thread — never rely on MDC propagation across threads.
     */
    public record AuthIdentity(String subscriberId, String recordId) {
        public static AuthIdentity anonymous() {
            return new AuthIdentity("anonymous", "anonymous");
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(AuthorizationService.class);

    /**
     * {@code WWW-Authenticate} challenge value for 401 responses (RFC 7235 §4.1).
     * The Beckn auth scheme is HTTP Signature ({@code "Signature "} header prefix),
     * so the challenge advertises the {@code Signature} scheme.
     */
    private static final String WWW_AUTHENTICATE_CHALLENGE = "Signature realm=\"beckn\"";

    private final BecknAuth becknAuth;
    private final AuthProperties authProperties;

    public AuthorizationService(BecknAuth becknAuth, AuthProperties authProperties) {
        this.becknAuth = becknAuth;
        this.authProperties = authProperties;
        logger.info("beckn.auth.verification {} | whitelistedEndpoints={}",
                authProperties.enabled() ? "ENABLED" : "DISABLED",
                authProperties.whitelistedEndpoints());
    }

    private boolean isWhitelisted(String method, String path) {
        return authProperties.whitelistedEndpoints().stream().anyMatch(entry -> {
            int colonIdx = entry.indexOf(':');
            if (colonIdx < 0) return false;
            String wMethod = entry.substring(0, colonIdx).trim().toUpperCase();
            String wPath = entry.substring(colonIdx + 1).trim();
            if (!wMethod.equals(method.toUpperCase())) return false;
            String[] patternParts = wPath.split("/");
            String[] pathParts = path.split("/");
            if (patternParts.length != pathParts.length) return false;
            for (int i = 0; i < patternParts.length; i++) {
                String p = patternParts[i];
                if (!p.startsWith(":") && !p.startsWith("{") && !p.equals(pathParts[i])) return false;
            }
            return true;
        });
    }

    /**
     * Verifies the Beckn HTTP Signature on the incoming request.
     *
     * <p>Returns the caller's {@link AuthIdentity} (subscriberId + recordId) so that the
     * controller can propagate identity across thread boundaries without relying on the
     * thread-local MDC (which is only set on the executor thread, not the calling thread).</p>
     *
     * @param rawBody the exact unmodified request body string
     * @param headers HTTP headers containing the Authorization header
     * @return caller identity; {@link AuthIdentity#anonymous()} when auth is disabled or endpoint whitelisted
     * @throws ErrorResponseException with 401 if signature is missing or invalid
     */
    public AuthIdentity authorizeRequest(String rawBody, HttpHeaders headers) {
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null && isWhitelisted(attrs.getRequest().getMethod(), attrs.getRequest().getServletPath())) {
            logger.info("{} reason=whitelisted method={} path={}",
                    LogEvent.AUTH_SKIPPED,
                    attrs.getRequest().getMethod(), attrs.getRequest().getServletPath());
            return AuthIdentity.anonymous();
        }

        if (!authProperties.enabled()) {
            logger.debug("{}", LogEvent.AUTH_DISABLED);
            return AuthIdentity.anonymous();
        }

        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
        logger.info("{}", LogEvent.AUTH_VERIFY_START);
        try {
            var result = becknAuth.verifySignature(authHeader, rawBody);
            var parsed = result.parsedHeader();
            logger.info("{} subscriberId={}", LogEvent.AUTH_VERIFY_DONE, parsed.subscriberId());
            BecknMdcContext.setAuthFields(parsed.subscriberId(), parsed.uniqueKeyId());
            return new AuthIdentity(parsed.subscriberId(), parsed.uniqueKeyId());
        } catch (BecknAuthException e) {
            logger.error("{} code={} message={}",
                    LogEvent.AUTH_FAILED,
                    ErrorSanitizer.sanitize(e.getCode()),
                    ErrorSanitizer.sanitize(e.getMessage()));
            // F-12: the beckn-auth SDK historically tags header/credential-level
            // authentication failures (absent header, unparseable header / bad keyId,
            // blank subscriber in keyId) as HTTP 400. Per RFC 7235 these are all
            // authentication failures and MUST be 401 Unauthorized with a
            // WWW-Authenticate challenge. The SDK is an auth library — every 400 it
            // produces is an auth-credential failure — so any SDK 400 is remapped to
            // 401. Its genuine server statuses (already-401 crypto/timestamp/key paths,
            // 502 registry-unreachable, 500 internal) pass through unchanged.
            int status = isAuthFailureMistaggedAs400(e)
                    ? HttpStatus.UNAUTHORIZED.value() : e.getHttpStatus();
            ProblemDetail pd = ProblemDetail.forStatus(status);
            pd.setDetail(e.getMessage());
            // Surface the canonical Beckn v2.0 AUT_* ErrorCode (beckn.yaml), translated
            // from the SDK's legacy SEC_* code, so the client-facing NACK is spec-compliant.
            pd.setProperty("code", toSpecAuthCode(e.getCode()));
            HttpStatusCode resolved = HttpStatusCode.valueOf(status);
            ErrorResponseException ere = new ErrorResponseException(resolved, pd, e);
            if (HttpStatus.UNAUTHORIZED.value() == status) {
                // RFC 7235 §3.1 — every 401 response must include a WWW-Authenticate challenge.
                // getHeaders() returns the live mutable header map; GlobalExceptionHandler
                // copies these onto the NACK response.
                ere.getHeaders().add(HttpHeaders.WWW_AUTHENTICATE, WWW_AUTHENTICATE_CHALLENGE);
            }
            throw ere;
        }
    }

    /**
     * Identifies an authentication failure the SDK mistagged as HTTP 400 that must
     * surface as 401 Unauthorized.
     *
     * <p>The beckn-auth-java-sdk is an authentication library: every exception it
     * raises is an auth failure. It emits 400 for credential/header-level failures
     * — absent header ({@code SEC_SIGNATURE_MISSING}), unparseable header / invalid
     * keyId ({@code SEC_SIGNATURE_INVALID}), and blank subscriber in keyId
     * ({@code SEC_SUBSCRIBER_NOT_FOUND}) — all of which are RFC 7235 401s. So any
     * SDK {@code httpStatus == 400} is an auth failure to be remapped to 401.</p>
     *
     * <p>The SDK's already-correct 401 paths (crypto-mismatch, timestamp-expired,
     * key-not-found / expired) and its genuine server statuses (502 registry, 500
     * internal) are <b>not</b> 400, so they pass through untouched.</p>
     */
    private static boolean isAuthFailureMistaggedAs400(BecknAuthException e) {
        return e.getHttpStatus() == HttpStatus.BAD_REQUEST.value();
    }

    /**
     * Translates the beckn-auth-java-sdk's legacy {@code SEC_*} authentication
     * error code to the canonical Beckn v2.0 {@code AUT_*} {@code ErrorCode} enum
     * value defined in beckn.yaml. The SDK predates the spec's {@code AUT_} prefix;
     * Discovr must surface spec-compliant codes to clients.
     *
     * <p>The default ({@link ErrorCodes#NET_INTERNAL_ERROR}) covers the SDK's
     * non-auth-credential codes that can reach the verify path — its own
     * {@code INTERNAL_ERROR} (500) and registry {@code NET_INTERNAL_ERROR} (502) —
     * which are server faults, not authentication failures, so the network-layer
     * code is the correct surface for them.</p>
     */
    private static String toSpecAuthCode(String sdkCode) {
        if (sdkCode == null) {
            return ErrorCodes.NET_INTERNAL_ERROR;
        }
        return switch (sdkCode) {
            case org.beckn.auth.util.ErrorCodes.SEC_SIGNATURE_MISSING      -> ErrorCodes.AUT_SIGNATURE_MISSING;
            case org.beckn.auth.util.ErrorCodes.SEC_SIGNATURE_INVALID      -> ErrorCodes.AUT_SIGNATURE_INVALID;
            case org.beckn.auth.util.ErrorCodes.SEC_SUBSCRIBER_NOT_FOUND   -> ErrorCodes.AUT_SUBSCRIBER_NOT_FOUND;
            case org.beckn.auth.util.ErrorCodes.SEC_KEY_NOT_FOUND          -> ErrorCodes.AUT_KEY_NOT_FOUND;
            case org.beckn.auth.util.ErrorCodes.SEC_KEY_EXPIRED_OR_REVOKED -> ErrorCodes.AUT_KEY_EXPIRED_OR_REVOKED;
            case org.beckn.auth.util.ErrorCodes.SEC_UNAUTHORIZED_ACTION    -> ErrorCodes.AUT_UNAUTHORIZED_ACTION;
            default -> ErrorCodes.NET_INTERNAL_ERROR;
        };
    }
}
