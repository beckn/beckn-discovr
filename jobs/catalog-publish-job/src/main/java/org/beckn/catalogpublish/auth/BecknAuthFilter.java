package org.beckn.catalogpublish.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.beckn.auth.BecknAuth;
import org.beckn.auth.exception.BecknAuthException;
import org.beckn.catalogpublish.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

public class BecknAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BecknAuthFilter.class);
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH");

    private final BecknAuth becknAuth;
    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;

    public BecknAuthFilter(BecknAuth becknAuth, AuthProperties authProperties, ObjectMapper objectMapper) {
        this.becknAuth = becknAuth;
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        if (!authProperties.enabled() || !MUTATING_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        byte[] bodyBytes = request.getInputStream().readAllBytes();
        String rawBody = new String(bodyBytes, StandardCharsets.UTF_8);
        String authHeader = request.getHeader("Authorization");

        log.info("catalog.push.auth.verify.start path={}", request.getRequestURI());
        try {
            var parsed = becknAuth.verifySignature(authHeader, rawBody);
            log.info("catalog.push.auth.verify.done path={} subscriberId={}", request.getRequestURI(), parsed.subscriberId());
        } catch (BecknAuthException e) {
            log.error("catalog.push.auth.verify.failed path={} code={} message={} authHeader={}",
                    request.getRequestURI(), e.getCode(), e.getMessage(), authHeader);
            sendNack(response, e.getHttpStatus(), e.getCode(), e.getMessage());
            return;
        }

        chain.doFilter(new CachedBodyRequestWrapper(request, bodyBytes), response);
    }

    private void sendNack(HttpServletResponse response, int httpStatus, String code, String message)
            throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> nack = Map.of(
                "status", "NACK",
                "error", Map.of("errorCode", code, "errorMessage", message));
        response.getWriter().write(objectMapper.writeValueAsString(nack));
    }
}
