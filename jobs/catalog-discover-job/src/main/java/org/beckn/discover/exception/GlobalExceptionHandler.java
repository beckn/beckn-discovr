package org.beckn.discover.exception;

import java.util.Map;

import org.beckn.discover.common.ErrorCodes;
import org.beckn.discover.common.ErrorMessages;
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
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import static net.logstash.logback.argument.StructuredArguments.value;

/**
 * Global exception handler — converts all unhandled exceptions into Beckn NACK responses.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so that Spring MVC exceptions
 * (e.g. {@code HttpMessageNotReadableException} from malformed JSON input) are
 * returned as {@code 400 Bad Request} NACK responses rather than the default
 * {@code 500 Internal Server Error}.</p>
 *
 * <h3>Exception handler priority</h3>
 * <ol>
 *   <li>{@link ResponseEntityExceptionHandler} — Spring MVC infrastructure exceptions (400/405/415…)</li>
 *   <li>{@link ErrorResponseException} — Beckn auth / validation errors with embedded code/paths</li>
 *   <li>{@link SemanticSearchException} — embedding/LLM provider unavailable → 503 NET_INTERNAL_ERROR</li>
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
                value("errorCode", ErrorCodes.NET_INTERNAL_ERROR),
                value("error", ex.getMessage()),
                value("cause", ex.getCause() != null ? ex.getCause().getMessage() : "none"),
                ex);
        AckResponse ackResponse = AckResponse.nack(ErrorCodes.NET_INTERNAL_ERROR, ErrorMessages.INTERNAL_SERVER_ERROR);
        return new ResponseEntity<>(ackResponse, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler({ IllegalArgumentException.class })
    public ResponseEntity<Object> handleBadRequest(Exception ex) {
        return buildErrorResponse(ex, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler({ Exception.class })
    public ResponseEntity<Object> handleInternal(Exception ex) {
        return buildErrorResponse(ex, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ── Shared builder ─────────────────────────────────────────────────────────

    private ResponseEntity<Object> buildErrorResponse(Exception ex, HttpStatusCode status) {
        String code;
        String message;

        if (ex instanceof ErrorResponseException ere) {
            ProblemDetail pd = ere.getBody();
            Map<String, Object> props = pd.getProperties();
            code = props != null && props.containsKey("code") ? (String) props.get("code")
                    : ErrorCodes.INTERNAL_ERROR;
            message = pd.getDetail();
        } else if (status == HttpStatus.BAD_REQUEST || ex instanceof IllegalArgumentException) {
            code = ErrorCodes.INVALID_REQUEST;
            message = sanitizeValidationMessage(ex.getMessage());
        } else {
            code = ErrorCodes.INTERNAL_ERROR;
            message = ErrorMessages.INTERNAL_SERVER_ERROR;
        }

        log.warn(LogEvent.NACK_RESPONSE,
                value("errorCode", code),
                value("httpStatus", status.value()),
                value("error", ex.getMessage()));

        AckResponse ackResponse = AckResponse.nack(code, message);
        return new ResponseEntity<>(ackResponse, status);
    }

    /**
     * Strips internal implementation details from validation error messages
     * before they reach the client. Removes "(paths: ...)" suffixes and
     * replaces null/empty messages with a generic fallback.
     */
    private static String sanitizeValidationMessage(String raw) {
        if (raw == null || raw.isBlank()) return ErrorMessages.VALIDATION_FAILED;
        // Strip "(paths: $.context, $.message)" suffix that leaks internal schema paths
        String cleaned = raw.replaceAll("\\s*\\(paths?:.*\\)$", "");
        return cleaned.isBlank() ? ErrorMessages.VALIDATION_FAILED : cleaned;
    }

}
