package org.beckn.discover.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.beckn.discover.common.ErrorCodes;
import org.beckn.discover.common.ErrorMessages;
import org.beckn.discover.filter.FilterCompiler;
import org.beckn.discover.filter.FilterParseException;
import org.beckn.discover.filter.UnsupportedFilterException;
import org.beckn.discover.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.NonTransientDataAccessException;
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
 * <p><b>Caching:</b> parseability is a pure function of the processed string (it never changes at
 * runtime), so the verdict is memoised in a bounded Caffeine cache. The first sighting of an
 * expression costs one parse-only round-trip; every repeat — the overwhelming majority of real
 * traffic — is a map lookup with no connection, no network, no blocking. Negative verdicts are
 * cached too, so a client spamming the same invalid expression cannot hammer Postgres. The cache
 * is bounded so a flood of unique expressions cannot grow the heap without limit. Postgres remains
 * the authority — we never substitute an in-process parser (Jayway/RFC-9535 dialects disagree with
 * PG's SQL/JSON path grammar and would reintroduce exactly this class of bug).</p>
 *
 * <p><b>Failure classification:</b> only {@link NonTransientDataAccessException} (a genuine parse
 * failure, SQLSTATE class 22) becomes a {@code 400}. Transient failures (Postgres unreachable, pool
 * exhausted, timeout) are NOT cached and propagate to the global handler as a 5xx — so a DB outage
 * never masquerades as "your valid expression is malformed". This mirrors the transient/
 * non-transient split already used by {@code PostgreSQLService.executeJsonPathQuery}.</p>
 *
 * <p>Scope is the JSONPath filter only — an <i>engine-dialect</i> check that genuinely needs the
 * engine's parser. Spatial coordinate validity (coordinates must be numbers) is a <i>structural</i>
 * check that needs no engine, so it lives with the other structural intent rules in
 * {@code DiscoveryValidationService} (alongside the {@code distanceMeters} and absolute-jsonpath
 * guards), surfacing as a normal schema-validation failure.</p>
 *
 * <p>The Postgres jsonpath probe is parse-only (no table access). It runs on the request thread so
 * the async POST path rejects synchronously, before the Kafka publish.</p>
 */
@Service
public class IntentQueryValidator {

    private static final Logger log = LoggerFactory.getLogger(IntentQueryValidator.class);

    private final JdbcClient jdbcClient;
    private final FilterCompiler filterCompiler;

    /**
     * processed-expression → is it a valid PG jsonpath. Validity is a pure function of the string,
     * so entries never expire; bounded size evicts adversarial one-off expressions.
     */
    private final Cache<String, Boolean> validityCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .build();

    public IntentQueryValidator(JdbcClient jdbcClient, FilterCompiler filterCompiler) {
        this.jdbcClient = jdbcClient;
        this.filterCompiler = filterCompiler;
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
        JsonNode filters = intent.path("filters");
        validateJsonPath(filters.path("expression"), filters.path("type").asText(null));
    }

    private void validateJsonPath(JsonNode expressionNode, String filterType) {
        if (!expressionNode.isTextual()) {
            return;
        }
        String expr = expressionNode.asText();
        if (expr.isBlank()) {
            return; // blank / absoluteness already guarded by DiscoveryValidationService
        }
        // Compile to exactly what the engine will run. For rfc9535 this parses + translates
        // (grammar/capability errors surface here as a clean NACK); for legacy jsonpath it is
        // the existing single→double quote / colon-field processing. Same compiler the consumer
        // uses, so validation and execution can never disagree on the dialect.
        String processed;
        try {
            processed = filterCompiler.toPgJsonPath(expr, filterType);
        } catch (FilterParseException | UnsupportedFilterException e) {
            log.warn(LogEvent.VALIDATE_FAILED + ".jsonpath", value("expression", expr),
                    value("type", filterType), value("reason", e.getMessage()));
            throw badRequest(ErrorCodes.SCH_INVALID_JSONPATH, ErrorMessages.SCH_INVALID_JSONPATH);
        }

        // Final authority: probe Postgres on the translated form (first sighting only; repeats are
        // free). A transient DB failure thrown by probe() propagates (not cached) → 5xx, never a
        // false 400.
        boolean valid = validityCache.get(processed, this::probe);
        if (!valid) {
            // Keep the offending expression in the log for debugging, but do NOT reflect it back
            // in the response detail.
            log.warn(LogEvent.VALIDATE_FAILED + ".jsonpath", value("expression", expr));
            throw badRequest(ErrorCodes.SCH_INVALID_JSONPATH, ErrorMessages.SCH_INVALID_JSONPATH);
        }
    }

    /**
     * Parse-only probe against Postgres — the authoritative SQL/JSON path validator.
     * Returns {@code false} only on a genuine parse failure; a transient DB error propagates so the
     * caller surfaces it as 5xx (and it is not cached as "invalid").
     */
    private boolean probe(String processed) {
        try {
            jdbcClient.sql("SELECT CAST(? AS jsonpath)").param(processed).query().listOfRows();
            return true;
        } catch (NonTransientDataAccessException e) {
            return false; // genuine parse failure → cache the negative verdict
        }
        // TransientDataAccessException (DB down / pool exhausted / timeout) propagates → 5xx.
    }

    private static ErrorResponseException badRequest(String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setProperty("code", code);
        pd.setDetail(detail);
        return new ErrorResponseException(HttpStatus.BAD_REQUEST, pd, null);
    }
}
