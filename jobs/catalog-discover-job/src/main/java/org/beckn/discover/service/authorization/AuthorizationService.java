package org.beckn.discover.service.authorization;

import org.beckn.auth.BecknAuth;
import org.beckn.auth.exception.BecknAuthException;
import org.beckn.discover.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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

    private static final Logger logger = LoggerFactory.getLogger(AuthorizationService.class);

    private final BecknAuth becknAuth;
    private final AuthProperties authProperties;

    public AuthorizationService(BecknAuth becknAuth, AuthProperties authProperties) {
        this.becknAuth = becknAuth;
        this.authProperties = authProperties;
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
     * @param rawBody the exact unmodified request body string
     * @param headers HTTP headers containing the Authorization header
     * @throws ErrorResponseException with 401 if signature is missing or invalid
     */
    public void authorizeRequest(String rawBody, HttpHeaders headers) {
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null && isWhitelisted(attrs.getRequest().getMethod(), attrs.getRequest().getRequestURI())) {
            logger.debug("auth.whitelisted path={}", attrs.getRequest().getRequestURI());
            return;
        }

        if (!authProperties.enabled()) {
            logger.debug("auth.disabled — skipping signature verification");
            return;
        }

        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
        logger.info("auth.verify.start");
        try {
            var parsed = becknAuth.verifySignature(authHeader, rawBody);
            logger.info("auth.verify.done subscriberId={}", parsed.subscriberId());
        } catch (BecknAuthException e) {
            logger.error("auth.verify.failed code={} message={} authHeader={}",
                    e.getCode(), e.getMessage(), authHeader);
            ProblemDetail pd = ProblemDetail.forStatus(e.getHttpStatus());
            pd.setDetail(e.getMessage());
            pd.setProperty("code", e.getCode());
            throw new ErrorResponseException(HttpStatusCode.valueOf(e.getHttpStatus()), pd, e);
        }
    }
}
