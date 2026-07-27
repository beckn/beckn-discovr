package org.beckn.catalogpublish.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.beckn.auth.BecknAuth;
import org.beckn.auth.exception.BecknAuthException;
import org.beckn.auth.util.ErrorCodes;
import org.beckn.catalogpublish.common.BecknFields;
import org.beckn.catalogpublish.config.AuthProperties;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
                    value("subscriberId", ErrorSanitizer.sanitize(parsed.parsedHeader().subscriberId())));
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
            // Emit the canonical Beckn v2.0 AUT_* ErrorCode (beckn.yaml), translated from the
            // SDK's legacy SEC_* code, so the client-facing NACK is spec-compliant.
            sendNack(response, httpStatus, toSpecAuthCode(code), messageForSdkCode(code), bodyBytes);
            return;
        } catch (Exception e) {
            // Catch-all: log full detail, send fixed constant to client (never raw exception message).
            log.error(LogEvent.AUTH_VERIFY_FAILED,
                    value("path", sanitizedPath),
                    value("code", org.beckn.catalogpublish.common.ErrorCodes.AUT_SIGNATURE_INVALID),
                    value("message", ErrorSanitizer.sanitize(e.getMessage())));
            sendNack(response, HttpServletResponse.SC_UNAUTHORIZED,
                    org.beckn.catalogpublish.common.ErrorCodes.AUT_SIGNATURE_INVALID,
                    org.beckn.catalogpublish.common.ErrorMessages.AUT_SIGNATURE_INVALID, bodyBytes);
            return;
        }

        chain.doFilter(new CachedBodyRequestWrapper(request, bodyBytes), response);
    }

    /**
     * Writes a spec-compliant NACK body:
     * {@code {"message":{"status":"NACK","messageId":"<id>","error":{"code":...,"message":...}}}}
     * The messageId is echoed from the request context when present, omitted otherwise.
     */
    private void sendNack(HttpServletResponse response, int httpStatus, String code, String message,
            byte[] bodyBytes) throws IOException {
        JsonNode ctx = parseContext(bodyBytes);

        Map<String, Object> error = new HashMap<>();
        error.put(BecknFields.CODE, code);
        error.put(BecknFields.MESSAGE, message);

        Map<String, Object> inner = new HashMap<>();
        inner.put(BecknFields.STATUS, "NACK");
        putIfPresent(inner, BecknFields.MESSAGE_ID, contextText(ctx, BecknFields.MESSAGE_ID));
        inner.put(BecknFields.ERROR, error);

        Map<String, Object> outer = new HashMap<>();
        outer.put(BecknFields.MESSAGE, inner);

        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Defense-in-depth: JSON body with an untrusted echoed messageId — stop browsers MIME-sniffing it as HTML.
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.getWriter().write(objectMapper.writeValueAsString(outer));
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    /** Best-effort parse of the request body's {@code context} object; {@code null} on any failure. */
    private JsonNode parseContext(byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) return null;
        try {
            JsonNode ctx = objectMapper.readTree(bodyBytes).path(BecknFields.CONTEXT);
            return ctx.isObject() ? ctx : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads {@code context.<field>} as non-blank text, or {@code null}. */
    private static String contextText(JsonNode ctx, String field) {
        if (ctx == null) return null;
        JsonNode v = ctx.path(field);
        return (v.isTextual() && !v.asText().isBlank()) ? v.asText() : null;
    }

    /** Translates the auth SDK's legacy {@code SEC_*} code to the canonical Beckn v2.0 {@code AUT_*} ErrorCode. */
    private static String toSpecAuthCode(String sdkCode) {
        if (sdkCode == null) return org.beckn.catalogpublish.common.ErrorCodes.NET_INTERNAL_ERROR;
        return switch (sdkCode) {
            case ErrorCodes.SEC_SIGNATURE_MISSING      -> org.beckn.catalogpublish.common.ErrorCodes.AUT_SIGNATURE_MISSING;
            case ErrorCodes.SEC_SIGNATURE_INVALID      -> org.beckn.catalogpublish.common.ErrorCodes.AUT_SIGNATURE_INVALID;
            case ErrorCodes.SEC_SUBSCRIBER_NOT_FOUND   -> org.beckn.catalogpublish.common.ErrorCodes.AUT_SUBSCRIBER_NOT_FOUND;
            case ErrorCodes.SEC_KEY_NOT_FOUND          -> org.beckn.catalogpublish.common.ErrorCodes.AUT_KEY_NOT_FOUND;
            case ErrorCodes.SEC_KEY_EXPIRED_OR_REVOKED -> org.beckn.catalogpublish.common.ErrorCodes.AUT_KEY_EXPIRED_OR_REVOKED;
            case ErrorCodes.SEC_UNAUTHORIZED_ACTION    -> org.beckn.catalogpublish.common.ErrorCodes.AUT_UNAUTHORIZED_ACTION;
            default -> org.beckn.catalogpublish.common.ErrorCodes.NET_INTERNAL_ERROR;
        };
    }

    /** Controlled, user-facing message for the given SDK {@code SEC_*} code. */
    private static String messageForSdkCode(String sdkCode) {
        if (sdkCode == null) return org.beckn.catalogpublish.common.ErrorMessages.NET_INTERNAL_ERROR;
        return switch (sdkCode) {
            case ErrorCodes.SEC_SIGNATURE_MISSING      -> org.beckn.catalogpublish.common.ErrorMessages.AUT_SIGNATURE_MISSING;
            case ErrorCodes.SEC_SIGNATURE_INVALID      -> org.beckn.catalogpublish.common.ErrorMessages.AUT_SIGNATURE_INVALID;
            case ErrorCodes.SEC_SUBSCRIBER_NOT_FOUND   -> org.beckn.catalogpublish.common.ErrorMessages.AUT_SUBSCRIBER_NOT_FOUND;
            case ErrorCodes.SEC_KEY_NOT_FOUND          -> org.beckn.catalogpublish.common.ErrorMessages.AUT_KEY_NOT_FOUND;
            case ErrorCodes.SEC_KEY_EXPIRED_OR_REVOKED -> org.beckn.catalogpublish.common.ErrorMessages.AUT_KEY_EXPIRED_OR_REVOKED;
            case ErrorCodes.SEC_UNAUTHORIZED_ACTION    -> org.beckn.catalogpublish.common.ErrorMessages.AUT_UNAUTHORIZED_ACTION;
            default -> org.beckn.catalogpublish.common.ErrorMessages.NET_INTERNAL_ERROR;
        };
    }
}
