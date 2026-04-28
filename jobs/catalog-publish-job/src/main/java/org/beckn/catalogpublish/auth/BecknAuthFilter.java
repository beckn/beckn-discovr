package org.beckn.catalogpublish.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.beckn.auth.BecknAuth;
import org.beckn.auth.exception.BecknAuthException;
import org.beckn.catalogpublish.config.AuthProperties;
import org.beckn.catalogpublish.logging.LogEvent;
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
        log.info("event={} enabled={} whitelistedEndpoints={}",
                LogEvent.AUTH_INIT,
                authProperties.enabled(),
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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        if (!authProperties.enabled()) {
            log.debug("{} reason=disabled method={} path={}", LogEvent.AUTH_SKIPPED, request.getMethod(), request.getServletPath());
            chain.doFilter(request, response);
            return;
        }
        if (!MUTATING_METHODS.contains(request.getMethod())) {
            log.info("{} reason=non-mutating method={} path={}", LogEvent.AUTH_SKIPPED, request.getMethod(), request.getServletPath());
            chain.doFilter(request, response);
            return;
        }
        if (isWhitelisted(request.getMethod(), request.getServletPath())) {
            log.info("{} reason=whitelisted method={} path={}", LogEvent.AUTH_SKIPPED, request.getMethod(), request.getServletPath());
            chain.doFilter(request, response);
            return;
        }

        byte[] bodyBytes = request.getInputStream().readAllBytes();
        String rawBody = new String(bodyBytes, StandardCharsets.UTF_8);
        String authHeader = request.getHeader("Authorization");

        log.info("{} path={}", LogEvent.AUTH_VERIFY_START, request.getServletPath());
        try {
            var parsed = becknAuth.verifySignature(authHeader, rawBody);
            log.info("{} path={} subscriberId={}", LogEvent.AUTH_VERIFY_DONE, request.getServletPath(), parsed.parsedHeader().subscriberId());
        } catch (BecknAuthException e) {
            log.error("{} path={} code={} message={}",
                    LogEvent.AUTH_VERIFY_FAILED, request.getServletPath(), e.getCode(), e.getMessage());
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
