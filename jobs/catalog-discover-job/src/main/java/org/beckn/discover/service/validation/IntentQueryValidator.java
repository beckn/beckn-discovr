package org.beckn.discover.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.beckn.discover.common.ErrorCodes;
import org.beckn.discover.common.ErrorMessages;
import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.InvalidRfc9535SyntaxException;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.Rfc9535FilterCompiler;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.UnsupportedConstructException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;

import static net.logstash.logback.argument.StructuredArguments.value;

/**
 * Validates the discover intent's JSONPath filter up front — before any query runs (GET) and
 * before the request is published to Kafka (POST) — turning an unparseable/unsupported
 * expression into a clean protocol NACK instead of a downstream crash with no callback.
 *
 * <p>Runs <b>after</b> {@code DiscoveryValidationService} structural/schema validation, so only
 * structurally-valid requests reach the grammar compiler here.</p>
 *
 * <p>Delegates entirely to {@link Rfc9535FilterCompiler}, which owns both grammars (RFC 9535 and
 * legacy Postgres jsonpath), the parse-priority fallback order, and the compiled-result cache —
 * see docs/design/DESIGN-rfc9535-jsonpath-grammar.md. This class is now a thin caller: a
 * successful compile (either grammar) lets the ACK path continue; a failure under both grammars
 * becomes a {@code 400} NACK with the failure's specific error code.</p>
 *
 * <p><b>Failure classification:</b> a transient DB error surfacing from the compiler's legacy
 * fallback probe (Postgres unreachable, pool exhausted, timeout) is NOT a
 * {@link InvalidRfc9535SyntaxException}/{@link UnsupportedConstructException} — it propagates
 * uncaught to the global handler as a 5xx, so a DB outage never masquerades as "your valid
 * expression is malformed".</p>
 */
@Service
public class IntentQueryValidator {

    private static final Logger log = LoggerFactory.getLogger(IntentQueryValidator.class);

    private final Rfc9535FilterCompiler filterCompiler;

    public IntentQueryValidator(Rfc9535FilterCompiler filterCompiler) {
        this.filterCompiler = filterCompiler;
    }

    /**
     * Throws {@link ErrorResponseException} (HTTP 400) when the intent's JSONPath filter
     * expression is invalid or uses an unsupported construct under both grammars.
     */
    public void validate(JsonNode requestNode) {
        if (requestNode == null) {
            return;
        }
        JsonNode intent = requestNode.path("message").path("intent");
        if (intent.isMissingNode() || intent.isNull()) {
            return;
        }
        validateJsonPath(intent.path("filters").path("expression"));
    }

    private void validateJsonPath(JsonNode expressionNode) {
        if (!expressionNode.isTextual()) {
            return;
        }
        String expr = expressionNode.asText();
        if (expr.isBlank()) {
            return; // blank / absoluteness already guarded by DiscoveryValidationService
        }
        try {
            filterCompiler.compile(expr);
        } catch (UnsupportedConstructException e) {
            log.warn(LogEvent.VALIDATE_FAILED + ".jsonpath.unsupported",
                    value("expression", expr), value("construct", e.construct()));
            throw badRequest(Rfc9535FilterCompiler.errorCodeFor(e.construct()), e.getMessage());
        } catch (InvalidRfc9535SyntaxException e) {
            log.warn(LogEvent.VALIDATE_FAILED + ".jsonpath", value("expression", expr));
            throw badRequest(ErrorCodes.SCH_INVALID_JSONPATH, ErrorMessages.SCH_INVALID_JSONPATH);
        }
    }

    private static ErrorResponseException badRequest(String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setProperty("code", code);
        pd.setDetail(detail);
        return new ErrorResponseException(HttpStatus.BAD_REQUEST, pd, null);
    }
}
