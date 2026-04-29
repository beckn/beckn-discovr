package org.beckn.catalogpublish.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.beckn.auth.BecknAuth;
import org.beckn.auth.exception.BecknAuthException;
import org.beckn.auth.util.ErrorCodes;
import org.beckn.auth.util.ErrorMessages;
import org.beckn.catalogpublish.config.AuthProperties;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.value;

public class BecknAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BecknAuthFilter.class);

    private final BecknAuth becknAuth;
    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;

    public BecknAuthFilter(BecknAuth becknAuth, AuthProperties authProperties, ObjectMapper objectMapper) {
        this.becknAuth = becknAuth;
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
        log.info(LogEvent.AUTH_INIT,
                value("enabled", authProperties.enabled()),
                value("whitelistedEndpoints", authProperties.whitelistedEndpoints()));
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
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI();
        return path.startsWith("/actuator/") || path.equals("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        var sanitizedPath = ErrorSanitizer.sanitize(request.getRequestURI());
        if (!authProperties.enabled()) {
            log.debug(LogEvent.AUTH_SKIPPED,
                    value("reason", "disabled"),
                    value("method", request.getMethod()),
                    value("path", sanitizedPath));
            chain.doFilter(request, response);
            return;
        }
        if (isWhitelisted(request.getMethod(), request.getRequestURI())) {
            log.debug(LogEvent.AUTH_SKIPPED,
                    value("reason", "whitelisted"),
                    value("method", request.getMethod()),
                    value("path", sanitizedPath));
            chain.doFilter(request, response);
            return;
        }

        byte[] bodyBytes = request.getInputStream().readAllBytes();
        String rawBody = new String(bodyBytes, StandardCharsets.UTF_8);
        String authHeader = request.getHeader("Authorization");

        log.info(LogEvent.AUTH_VERIFY_START, value("path", sanitizedPath));
        try {
            var parsed = becknAuth.verifySignature(authHeader, rawBody);
            log.info(LogEvent.AUTH_VERIFY_DONE,
                    value("path", sanitizedPath),
                    value("subscriberId", parsed.parsedHeader().subscriberId()));
        } catch (BecknAuthException e) {
            // SDK misclassifies some verification failures (e.g. illegal Base64) as INTERNAL_ERROR / 500.
            // Remap to SEC_SIGNATURE_INVALID / 401 when the cause is clearly a verification failure.
            var code = e.getCode();
            var httpStatus = e.getHttpStatus();
            if (ErrorCodes.INTERNAL_ERROR.equals(code) && e.getMessage() != null
                    && e.getMessage().toLowerCase().contains("verification")) {
                code = ErrorCodes.SEC_SIGNATURE_INVALID;
                httpStatus = HttpServletResponse.SC_UNAUTHORIZED;
            }
            log.error(LogEvent.AUTH_VERIFY_FAILED,
                    value("path", sanitizedPath),
                    value("code", code),
                    value("message", e.getMessage()));
            sendNack(response, httpStatus, code, safeMessageForCode(code));
            return;
        } catch (Exception e) {
            // Catch-all: log full detail, send fixed constant to client (never raw exception message).
            log.error(LogEvent.AUTH_VERIFY_FAILED,
                    value("path", sanitizedPath),
                    value("code", ErrorCodes.SEC_SIGNATURE_INVALID),
                    value("message", ErrorSanitizer.sanitize(e.getMessage())));
            sendNack(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ErrorCodes.SEC_SIGNATURE_INVALID,
                    ErrorMessages.AUTH_VERIFICATION_FAILED);
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

    private static String safeMessageForCode(String code) {
        return switch (code) {
            case ErrorCodes.SEC_SIGNATURE_MISSING -> ErrorMessages.AUTH_HEADER_MISSING;
            case ErrorCodes.SEC_SIGNATURE_INVALID -> ErrorMessages.AUTH_VERIFICATION_FAILED;
            case ErrorCodes.SEC_SUBSCRIBER_NOT_FOUND -> ErrorMessages.AUTH_SUBSCRIBER_NOT_FOUND;
            case ErrorCodes.SEC_KEY_NOT_FOUND -> ErrorMessages.REGISTRY_RECORD_NOT_FOUND;
            case ErrorCodes.SEC_KEY_EXPIRED_OR_REVOKED -> ErrorMessages.AUTH_PUBLIC_KEY_EXPIRED;
            case ErrorCodes.NET_INTERNAL_ERROR -> ErrorMessages.REGISTRY_CONNECTION_ERROR;
            case ErrorCodes.SEC_UNAUTHORIZED_ACTION -> ErrorMessages.AUTH_UNAUTHORIZED_ACTION;
            default -> ErrorMessages.INTERNAL_SERVER_ERROR;
        };
    }
}
