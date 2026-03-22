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

    /**
     * Verifies the Beckn HTTP Signature on the incoming request.
     *
     * @param rawBody the exact unmodified request body string
     * @param headers HTTP headers containing the Authorization header
     * @throws ErrorResponseException with 401 if signature is missing or invalid
     */
    public void authorizeRequest(String rawBody, HttpHeaders headers) {
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
