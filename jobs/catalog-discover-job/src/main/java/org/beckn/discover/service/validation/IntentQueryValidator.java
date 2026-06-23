package org.beckn.discover.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.beckn.discover.common.ErrorCodes;
import org.beckn.discover.common.ErrorMessages;
import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;

import static net.logstash.logback.argument.StructuredArguments.value;

/**
 * Validates the discover intent's JSONPath filter up front — before any query runs (GET) and
 * before the request is published to Kafka (POST) — turning an unparseable expression into a
 * clean protocol NACK instead of a downstream crash with no callback.
 *
 * <p>Runs <b>after</b> {@code DiscoveryValidationService} structural/schema validation, so only
 * structurally-valid requests reach the engine-dialect probe here.</p>
 *
 * <p>The engine runs the filter through {@link JsonPathConverter#processFilter} (single→double
 * quotes, colon-field quoting) and then {@code CAST(? AS jsonpath)}. We validate the
 * <b>processed</b> form against Postgres (the single source of truth for its SQL/JSON path
 * dialect), so a value Postgres cannot parse is rejected with {@code SCH_INVALID_JSONPATH}
 * instead of throwing a {@code PSQLException} inside the async query (which previously produced
 * no callback).</p>
 *
 * <p>Spatial geometry is intentionally NOT validated here yet. A separate gate for
 * structurally-malformed GeoJSON is planned, but is deferred: PostGIS
 * {@code ST_GeomFromGeoJSON} is highly lenient (it coerces string-encoded coordinates rather
 * than failing), so the crash surface is narrow, and the spatial validation contract is still
 * being aligned with the test harness.</p>
 *
 * <p>The Postgres probe is parse-only (no table access). It must run on the request thread so the
 * async POST path rejects synchronously, before the Kafka publish.</p>
 */
@Service
public class IntentQueryValidator {

    private static final Logger log = LoggerFactory.getLogger(IntentQueryValidator.class);

    private final JdbcClient jdbcClient;
    private final JsonPathConverter jsonPathConverter;

    public IntentQueryValidator(JdbcClient jdbcClient, JsonPathConverter jsonPathConverter) {
        this.jdbcClient = jdbcClient;
        this.jsonPathConverter = jsonPathConverter;
    }

    /**
     * Throws {@link ErrorResponseException} (HTTP 400) carrying {@code SCH_INVALID_JSONPATH} when
     * the intent's JSONPath filter expression is invalid for the query engine.
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
        // Validate exactly what the engine will run: the processed form, then the jsonpath cast.
        String processed = jsonPathConverter.processFilter(expr);
        try {
            jdbcClient.sql("SELECT CAST(? AS jsonpath)").param(processed).query().listOfRows();
        } catch (DataAccessException e) {
            log.warn(LogEvent.VALIDATE_FAILED + ".jsonpath", value("expression", expr));
            throw badRequest(ErrorCodes.SCH_INVALID_JSONPATH,
                    ErrorMessages.SCH_INVALID_JSONPATH + " (expression: " + expr + ")");
        }
    }

    private static ErrorResponseException badRequest(String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setProperty("code", code);
        pd.setDetail(detail);
        return new ErrorResponseException(HttpStatus.BAD_REQUEST, pd, null);
    }
}
