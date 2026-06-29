package org.beckn.discover.service.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.ErrorResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IntentQueryValidator} — the JSONPath filter parse gate.
 *
 * <p>Covers the two behaviours raised in review:
 * <ol>
 *   <li>A genuine parse failure ({@code NonTransientDataAccessException}) → 400 SCH_INVALID_JSONPATH.</li>
 *   <li>A transient DB failure ({@code TransientDataAccessException}) → propagates (NOT a false 400),
 *       and is not cached.</li>
 *   <li>Repeat verdicts are served from the cache (no second DB probe).</li>
 * </ol>
 */
class IntentQueryValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JdbcClient jdbcClient;
    private JdbcClient.StatementSpec stmt;
    private JdbcClient.ResultQuerySpec query;
    private IntentQueryValidator validator;

    @BeforeEach
    void setup() {
        jdbcClient = mock(JdbcClient.class);
        stmt = mock(JdbcClient.StatementSpec.class);
        query = mock(JdbcClient.ResultQuerySpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(stmt);
        when(stmt.param(org.mockito.ArgumentMatchers.any())).thenReturn(stmt);
        when(stmt.query()).thenReturn(query);

        // processFilter is identity for the test (we assert classification, not the transform)
        JsonPathConverter converter = mock(JsonPathConverter.class);
        when(converter.processFilter(anyString())).thenAnswer(i -> i.getArgument(0));

        validator = new IntentQueryValidator(jdbcClient, converter);
    }

    private com.fasterxml.jackson.databind.JsonNode req(String expr) throws Exception {
        return mapper.readTree(
                "{\"message\":{\"intent\":{\"filters\":{\"type\":\"jsonpath\",\"expression\":\"" + expr + "\"}}}}");
    }

    @Test
    void validExpression_passes() throws Exception {
        when(query.listOfRows()).thenReturn(java.util.List.of());
        assertThatCode(() -> validator.validate(req("$ ? (@.x == 1)"))).doesNotThrowAnyException();
    }

    @Test
    void parseFailure_throws400InvalidJsonpath() throws Exception {
        when(query.listOfRows()).thenThrow(new InvalidDataAccessApiUsageException("syntax error at or near \"?\""));
        assertThatThrownBy(() -> validator.validate(req("$[?(@.x==1)]")))
                .isInstanceOf(ErrorResponseException.class)
                .satisfies(e -> {
                    var ex = (ErrorResponseException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getBody().getProperties().get("code")).isEqualTo("SCH_INVALID_JSONPATH");
                });
    }

    @Test
    void transientDbFailure_propagates_notFalse400() throws Exception {
        when(query.listOfRows()).thenThrow(new TransientDataAccessResourceException("connection reset"));
        // Must NOT be converted to a 400 — the DB outage propagates to the global handler (→ 5xx).
        assertThatThrownBy(() -> validator.validate(req("$ ? (@.x == 1)")))
                .isInstanceOf(TransientDataAccessResourceException.class);
    }

    @Test
    void verdictIsCached_secondCallDoesNotProbeAgain() throws Exception {
        when(query.listOfRows()).thenReturn(java.util.List.of());
        validator.validate(req("$ ? (@.x == 1)"));
        validator.validate(req("$ ? (@.x == 1)"));
        // Same processed expression → probed once, served from cache the second time.
        verify(jdbcClient, times(1)).sql(anyString());
    }
}
