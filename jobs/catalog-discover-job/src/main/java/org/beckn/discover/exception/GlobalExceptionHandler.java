package org.beckn.discover.exception;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import org.beckn.discover.common.ErrorCodes;
import org.beckn.discover.common.ErrorMessages;
import org.beckn.discover.controller.DiscoveryController;
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
 *   <li>{@link IllegalArgumentException} — schema validation failures → 400</li>
 *   <li>{@link Exception} — catch-all → 500</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final java.util.regex.Pattern PATHS_PATTERN = java.util.regex.Pattern
            .compile("\\(paths:\\s*([^)]+)\\)");
    private static final java.util.regex.Pattern ERROR_PATH_PATTERN = java.util.regex.Pattern
            .compile("\\$\\.([\\w.]+):");

    // ── Spring MVC infrastructure exceptions (malformed JSON, wrong method, …) ─

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return buildErrorResponse(ex, status, request);
    }

    // ── Application exceptions ─────────────────────────────────────────────────

    @ExceptionHandler({ IllegalArgumentException.class })
    public ResponseEntity<Object> handleBadRequest(Exception ex, WebRequest request) {
        return buildErrorResponse(ex, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler({ Exception.class })
    public ResponseEntity<Object> handleInternal(Exception ex, WebRequest request) {
        return buildErrorResponse(ex, HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    // ── Shared builder ─────────────────────────────────────────────────────────

    private ResponseEntity<Object> buildErrorResponse(Exception ex, HttpStatusCode status, WebRequest webRequest) {
        String transactionId = extractTransactionId(ex, webRequest);
        String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String code;
        String paths;
        String message;

        if (ex instanceof ErrorResponseException ere) {
            ProblemDetail pd = ere.getBody();
            Map<String, Object> props = pd.getProperties();
            code = props != null && props.containsKey("code") ? (String) props.get("code")
                    : ErrorCodes.INTERNAL_ERROR;
            paths = props != null && props.containsKey("paths") ? (String) props.get("paths") : "server";
            String txnId = props != null && props.containsKey("transactionId") ? (String) props.get("transactionId")
                    : null;
            if (txnId != null) transactionId = txnId;
            message = pd.getDetail();
        } else if (status == HttpStatus.BAD_REQUEST || ex instanceof IllegalArgumentException) {
            code = ErrorCodes.INVALID_REQUEST;
            String field = extractInvalidFieldFromMessage(ex.getMessage());
            paths = (field != null && !field.isEmpty()) ? field : "context.schema_context";
            message = ex.getMessage();
        } else {
            code = ErrorCodes.INTERNAL_ERROR;
            paths = "server";
            message = ErrorMessages.INTERNAL_SERVER_ERROR;
        }

        AckResponse ackResponse = AckResponse.nack(transactionId, timestamp, code, paths, message);
        return new ResponseEntity<>(ackResponse, status);
    }

    /**
     * Resolves the transaction ID for the NACK response.
     *
     * <p>Priority:</p>
     * <ol>
     *   <li>Request attribute {@value DiscoveryController#TRANSACTION_ID_ATTR} — set by the
     *       controller early in the pipeline before any exception can be thrown.</li>
     *   <li>Random UUID fallback when no attribute is available (e.g. errors before parsing).</li>
     * </ol>
     */
    private String extractTransactionId(Exception ex, WebRequest webRequest) {
        if (webRequest != null) {
            Object attr = webRequest.getAttribute(DiscoveryController.TRANSACTION_ID_ATTR, WebRequest.SCOPE_REQUEST);
            if (attr instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return UUID.randomUUID().toString();
    }

    private String extractInvalidFieldFromMessage(String msg) {
        if (msg == null) return null;

        java.util.regex.Matcher pathsMatcher = PATHS_PATTERN.matcher(msg);
        if (pathsMatcher.find()) {
            String pathsStr = pathsMatcher.group(1).trim();
            String normalizedPath = pathsStr.replaceAll("^\\$\\.?", "").replaceAll("\\$\\.", "");
            String[] paths = normalizedPath.split(",\\s*");
            return paths[0].trim();
        }

        java.util.regex.Matcher errorPathMatcher = ERROR_PATH_PATTERN.matcher(msg);
        if (errorPathMatcher.find()) {
            return errorPathMatcher.group(1);
        }

        return null;
    }
}
