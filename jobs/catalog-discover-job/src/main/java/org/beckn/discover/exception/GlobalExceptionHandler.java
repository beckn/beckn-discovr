package org.beckn.discover.exception;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.beckn.discover.common.ErrorCodes;
import org.beckn.discover.common.ErrorMessages;
import org.beckn.discover.controller.DiscoveryController;
import org.beckn.discover.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.beckn.discover.model.AckResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import static net.logstash.logback.argument.StructuredArguments.value;

/**
 * Global exception handler — converts all unhandled exceptions into Beckn NACK responses.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so that Spring MVC infrastructure
 * exceptions (wrong method, unsupported media type, …) are returned as Beckn NACK
 * responses rather than Spring's default error body. Note that malformed JSON does NOT
 * arrive as {@code HttpMessageNotReadableException} here: the controller reads the raw
 * request bytes and parses them itself, so a bad body surfaces as a
 * {@link JsonProcessingException} handled explicitly below.</p>
 *
 * <h3>Exception handler priority</h3>
 * <p>(Spring resolves by exception-type specificity, not declaration order — the list
 * below is the conceptual most-specific-first grouping.)</p>
 * <ol>
 *   <li>{@link ResponseEntityExceptionHandler} — Spring MVC infrastructure exceptions (400/405/415…)</li>
 *   <li>{@link ErrorResponseException} — Beckn auth / validation errors with embedded code/paths</li>
 *   <li>{@link SemanticSearchException} — embedding/LLM provider unavailable → 500 NET_DOWNSTREAM_UNAVAILABLE</li>
 *   <li>{@link JsonProcessingException} — malformed request body → 400 SCH_INVALID_JSON</li>
 *   <li>{@link IllegalArgumentException} — schema validation failures → 400</li>
 *   <li>{@link Exception} — catch-all → 500</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ── Spring MVC infrastructure exceptions (malformed JSON, wrong method, …) ─

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return buildErrorResponse(ex, status);
    }

    // ── Application exceptions ─────────────────────────────────────────────────

    @ExceptionHandler({ SemanticSearchException.class })
    public ResponseEntity<Object> handleSemanticSearchFailure(SemanticSearchException ex, WebRequest request) {
        log.error(LogEvent.NACK_RESPONSE,
                value("errorCode", ErrorCodes.NET_DOWNSTREAM_UNAVAILABLE),
                value("error", ex.getMessage()),
                value("cause", ex.getCause() != null ? ex.getCause().getMessage() : "none"),
                ex);
        AckResponse ackResponse = AckResponse.nack(currentMessageId(), currentTransactionId(),
                ErrorCodes.NET_DOWNSTREAM_UNAVAILABLE,
                ErrorMessages.NET_SEARCH_SERVICE_UNAVAILABLE);
        // Spec maps transient server-side failures to 500 ServerError; /discover does not
        // declare a 503 response. Both unavailability cases surface as 500.
        return new ResponseEntity<>(ackResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler({ SchemaNotInitializedException.class })
    public ResponseEntity<Object> handleSchemaNotInitialized(SchemaNotInitializedException ex, WebRequest request) {
        log.error(LogEvent.NACK_RESPONSE,
                value("errorCode", ErrorCodes.NET_DOWNSTREAM_UNAVAILABLE),
                value("error", ex.getMessage()));
        AckResponse ackResponse = AckResponse.nack(currentMessageId(), currentTransactionId(),
                ErrorCodes.NET_DOWNSTREAM_UNAVAILABLE,
                ErrorMessages.NET_DOWNSTREAM_UNAVAILABLE);
        // Spec maps transient server-side failures to 500 ServerError; /discover does not
        // declare a 503 response. Both unavailability cases surface as 500.
        return new ResponseEntity<>(ackResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler({ IllegalArgumentException.class })
    public ResponseEntity<Object> handleBadRequest(Exception ex) {
        return buildErrorResponse(ex, HttpStatus.BAD_REQUEST);
    }

    /**
     * Malformed request body. The controller reads the raw bytes and parses them with
     * {@code objectMapper.readTree(...)}, so a syntactically invalid body surfaces as a
     * {@link JsonProcessingException} (NOT Spring's {@code HttpMessageNotReadableException}).
     * An unparseable payload is a client error → {@code 400} NACK with {@code SCH_INVALID_JSON}.
     * messageId / transactionId are omitted because they could not be parsed from the body.
     */
    @ExceptionHandler({ JsonProcessingException.class })
    public ResponseEntity<Object> handleMalformedJson(JsonProcessingException ex) {
        log.warn(LogEvent.NACK_RESPONSE,
                value("errorCode", ErrorCodes.SCH_INVALID_JSON),
                value("httpStatus", HttpStatus.BAD_REQUEST.value()),
                value("error", ex.getOriginalMessage()));
        AckResponse ackResponse = AckResponse.nack(currentMessageId(), currentTransactionId(),
                ErrorCodes.SCH_INVALID_JSON, ErrorMessages.SCH_INVALID_JSON);
        return new ResponseEntity<>(ackResponse, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler({ Exception.class })
    public ResponseEntity<Object> handleInternal(Exception ex) {
        return buildErrorResponse(ex, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ── Shared builder ─────────────────────────────────────────────────────────

    private ResponseEntity<Object> buildErrorResponse(Exception ex, HttpStatusCode status) {
        String code;
        String message;
        HttpHeaders responseHeaders = null;

        if (ex instanceof ErrorResponseException ere) {
            ProblemDetail pd = ere.getBody();
            Map<String, Object> props = pd.getProperties();
            code = props != null && props.containsKey("code") ? (String) props.get("code")
                    : ErrorCodes.NET_INTERNAL_ERROR;
            // Use the SDK-provided detail message when available — it contains the specific,
            // controlled format error message (e.g. "Authorization header format is invalid",
            // "Invalid keyId format"). Fall back to the generic constant for unknown codes.
            String detail = pd.getDetail();
            message = (detail != null && !detail.isBlank()) ? detail : safeMessageForCode(code);
            // Preserve any response headers the exception carries — e.g. the RFC 7235
            // WWW-Authenticate challenge attached to 401 auth failures.
            if (!ere.getHeaders().isEmpty()) {
                responseHeaders = ere.getHeaders();
            }
        } else if (status == HttpStatus.BAD_REQUEST || ex instanceof IllegalArgumentException) {
            code = ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED;
            message = sanitizeValidationMessage(ex.getMessage());
        } else {
            code = ErrorCodes.NET_INTERNAL_ERROR;
            message = ErrorMessages.NET_INTERNAL_ERROR;
        }

        log.warn(LogEvent.NACK_RESPONSE,
                value("errorCode", code),
                value("httpStatus", status.value()),
                value("error", ex.getMessage()));

        AckResponse ackResponse = AckResponse.nack(currentMessageId(), currentTransactionId(), code, message);
        return responseHeaders != null
                ? new ResponseEntity<>(ackResponse, responseHeaders, status)
                : new ResponseEntity<>(ackResponse, status);
    }

    /** Best-effort: request messageId stored as a servlet attribute by {@link DiscoveryController}. */
    private static String currentMessageId() {
        return requestAttr(DiscoveryController.MESSAGE_ID_ATTR);
    }

    /** Best-effort: request transactionId stored as a servlet attribute by {@link DiscoveryController}. */
    private static String currentTransactionId() {
        return requestAttr(DiscoveryController.TRANSACTION_ID_ATTR);
    }

    /**
     * Reads a String request attribute via {@link org.springframework.web.context.request.RequestContextHolder}.
     * Returns {@code null} when the request/attribute is unavailable (e.g. malformed JSON) — the
     * corresponding response field is then omitted rather than fabricated.
     */
    private static String requestAttr(String name) {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            Object v = sra.getRequest().getAttribute(name);
            return (v instanceof String s && !s.isBlank()) ? s : null;
        }
        return null;
    }

    /**
     * Maps an error code to a controlled, user-facing message constant.
     * Prevents raw SDK/internal messages from leaking to clients.
     */
    private static String safeMessageForCode(String code) {
        if (code == null) return ErrorMessages.NET_INTERNAL_ERROR;
        return switch (code) {
            case ErrorCodes.AUT_SIGNATURE_MISSING -> ErrorMessages.AUT_SIGNATURE_MISSING;
            case ErrorCodes.AUT_SIGNATURE_INVALID -> ErrorMessages.AUT_SIGNATURE_INVALID;
            case ErrorCodes.AUT_SUBSCRIBER_NOT_FOUND -> ErrorMessages.AUT_SUBSCRIBER_NOT_FOUND;
            case ErrorCodes.AUT_KEY_NOT_FOUND -> ErrorMessages.AUT_KEY_NOT_FOUND;
            case ErrorCodes.AUT_KEY_EXPIRED_OR_REVOKED -> ErrorMessages.AUT_KEY_EXPIRED_OR_REVOKED;
            case ErrorCodes.AUT_UNAUTHORIZED_ACTION -> ErrorMessages.AUT_UNAUTHORIZED_ACTION;
            case ErrorCodes.SCH_SCHEMA_VALIDATION_FAILED -> ErrorMessages.SCH_SCHEMA_VALIDATION_FAILED;
            case ErrorCodes.SCH_REQUIRED_FIELD_MISSING -> ErrorMessages.SCH_REQUIRED_FIELD_MISSING;
            case ErrorCodes.CTX_INVALID_FIELD -> ErrorMessages.CTX_INVALID_FIELD;
            case ErrorCodes.NET_INTERNAL_ERROR -> ErrorMessages.NET_INTERNAL_ERROR;
            default -> ErrorMessages.NET_INTERNAL_ERROR;
        };
    }

    /**
     * Strips internal implementation details from validation error messages
     * before they reach the client. Removes Spring method signatures, schema paths,
     * and replaces null/empty messages with a generic fallback.
     */
    private static String sanitizeValidationMessage(String raw) {
        if (raw == null || raw.isBlank()) return ErrorMessages.SCH_SCHEMA_VALIDATION_FAILED;
        // Spring appends the full controller method signature after ":"  — never expose it
        int colonIdx = raw.indexOf(':');
        if (colonIdx > 0 && raw.substring(colonIdx).contains("org.")) {
            return ErrorMessages.SCH_REQUIRED_FIELD_MISSING;
        }
        // Strip "(paths: $.context, $.message)" suffix that leaks internal schema paths
        String cleaned = raw.replaceAll("\\s*\\(paths?:.*\\)$", "");
        return cleaned.isBlank() ? ErrorMessages.SCH_SCHEMA_VALIDATION_FAILED : cleaned;
    }

}
