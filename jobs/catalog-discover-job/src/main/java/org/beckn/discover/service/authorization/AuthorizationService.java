package org.beckn.discover.service.authorization;

import org.beckn.auth.BecknAuth;
import org.beckn.auth.exception.BecknAuthException;
import org.beckn.discover.config.AuthProperties;
import org.beckn.discover.logging.BecknMdcContext;
import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** Beckn HTTP Signature header keyId pattern: keyId="subscriberId|recordId|algorithm". */
    private static final Pattern KEY_ID_PATTERN = Pattern.compile("keyId=\"([^\"]+)\"");

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

        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);

        if (!authProperties.enabled()) {
            // Onix in front already verified the signature cryptographically; we only need
            // to extract the caller's identity from the keyId. Falls back to anonymous when
            // the header is missing/malformed (matches prior behaviour).
            return parseIdentityFromKeyId(authHeader)
                    .map(identity -> {
                        logger.info("{} mode=parse-only subscriberId={}",
                                LogEvent.AUTH_VERIFY_DONE, identity.subscriberId());
                        BecknMdcContext.setAuthFields(identity.subscriberId(), identity.recordId());
                        return identity;
                    })
                    .orElseGet(() -> {
                        logger.debug("{}", LogEvent.AUTH_DISABLED);
                        return AuthIdentity.anonymous();
                    });
        }

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
            ProblemDetail pd = ProblemDetail.forStatus(e.getHttpStatus());
            pd.setDetail(e.getMessage());
            pd.setProperty("code", e.getCode());
            throw new ErrorResponseException(HttpStatusCode.valueOf(e.getHttpStatus()), pd, e);
        }
    }

    /**
     * Extracts the {@link AuthIdentity} (subscriberId + recordId) from the Beckn HTTP Signature
     * {@code keyId} parameter without performing cryptographic verification. Used when an upstream
     * component (e.g. onix) has already verified the signature and this service only needs the
     * caller's identity.
     *
     * <p>keyId format: {@code keyId="<subscriberId>|<recordId>|<algorithm>"}.
     * Returns {@link Optional#empty()} when the header is missing, has no {@code keyId} parameter,
     * or the keyId doesn't contain at least two pipe-separated non-blank segments.</p>
     */
    private static Optional<AuthIdentity> parseIdentityFromKeyId(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) return Optional.empty();
        Matcher m = KEY_ID_PATTERN.matcher(authHeader);
        if (!m.find()) return Optional.empty();
        String[] parts = m.group(1).split("\\|", 3);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) return Optional.empty();
        return Optional.of(new AuthIdentity(parts[0].trim(), parts[1].trim()));
    }
}
