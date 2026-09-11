package org.beckn.discover.service.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathConverter;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.FilterGrammarMetrics;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.Rfc9535FilterCompiler;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.Rfc9535SqlPredicateCompiler;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.UnsupportedConstructDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IntentQueryValidator} — the JSONPath filter grammar gate.
 *
 * <p>Expressions here are chosen to fail RFC 9535 parsing outright (e.g. {@code "$[??]"}), so
 * the legacy Postgres-jsonpath fallback probe — driven by a mocked {@link DataSource}/
 * {@link PreparedStatement} chain — is what actually gets exercised, mirroring this class's
 * pre-RFC-9535 behaviour:
 * <ol>
 *   <li>A genuine parse failure under both grammars ({@code NonTransientDataAccessException}
 *       from the legacy probe) → 400 {@code SCH_INVALID_JSONPATH}.</li>
 *   <li>A transient DB failure from the legacy probe → propagates (NOT a false 400), and is not
 *       cached.</li>
 *   <li>Repeat verdicts are served from {@link Rfc9535FilterCompiler}'s cache (no second probe).</li>
 * </ol>
 */
class IntentQueryValidatorTest {

    private static final String NOT_VALID_RFC9535 = "$[??]";

    private final ObjectMapper mapper = new ObjectMapper();

    private DataSource dataSource;
    private PreparedStatement preparedStatement;
    private IntentQueryValidator validator;

    @BeforeEach
    void setup() throws SQLException {
        dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        preparedStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        Rfc9535FilterCompiler filterCompiler = new Rfc9535FilterCompiler(
                new Rfc9535SqlPredicateCompiler(),
                new UnsupportedConstructDetector(),
                new JsonPathConverter(),
                dataSource,
                new DiscoveryProperties(),
                new FilterGrammarMetrics(new SimpleMeterRegistry()));
        validator = new IntentQueryValidator(filterCompiler);
    }

    private com.fasterxml.jackson.databind.JsonNode req(String expr) throws Exception {
        return mapper.readTree(
                "{\"message\":{\"intent\":{\"filters\":{\"type\":\"jsonpath\",\"expression\":\"" + expr + "\"}}}}");
    }

    @Test
    void validExpression_passes() throws Exception {
        // Valid RFC 9535 — compiles without ever touching the legacy DB probe.
        assertThatCode(() -> validator.validate(req("$.offers[?(@.price < 100)]")))
                .doesNotThrowAnyException();
        verifyNoInteractions(dataSource);
    }

    @Test
    void legacyOnlyExpression_stillPasses() throws Exception {
        // Not valid RFC 9535 syntax, but the legacy probe (mocked) accepts it.
        assertThatCode(() -> validator.validate(req(NOT_VALID_RFC9535))).doesNotThrowAnyException();
    }

    @Test
    void parseFailure_throws400InvalidJsonpath() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new InvalidDataAccessApiUsageException("syntax error at or near \"?\""));
        assertThatThrownBy(() -> validator.validate(req(NOT_VALID_RFC9535)))
                .isInstanceOf(ErrorResponseException.class)
                .satisfies(e -> {
                    var ex = (ErrorResponseException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getBody().getProperties().get("code")).isEqualTo("SCH_INVALID_JSONPATH");
                });
    }

    @Test
    void transientDbFailure_propagates_notFalse400() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new TransientDataAccessResourceException("connection reset"));
        // Must NOT be converted to a 400 — the DB outage propagates to the global handler (→ 5xx).
        assertThatThrownBy(() -> validator.validate(req(NOT_VALID_RFC9535)))
                .isInstanceOf(TransientDataAccessResourceException.class);
    }

    @Test
    void verdictIsCached_secondCallDoesNotProbeAgain() throws Exception {
        validator.validate(req(NOT_VALID_RFC9535));
        validator.validate(req(NOT_VALID_RFC9535));
        // Same expression → probed once, served from cache the second time.
        verify(dataSource, times(1)).getConnection();
    }
}
