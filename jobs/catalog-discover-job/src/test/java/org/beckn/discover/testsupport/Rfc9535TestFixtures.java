package org.beckn.discover.testsupport;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathConverter;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.FilterGrammarMetrics;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.Rfc9535FilterCompiler;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.Rfc9535SqlPredicateCompiler;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.UnsupportedConstructDetector;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared unit-test wiring for {@link Rfc9535FilterCompiler}, used by callers (e.g.
 * {@code JsonPathQueryBuilder}) that need a real compiler but a stubbed database.
 */
public final class Rfc9535TestFixtures {

    private Rfc9535TestFixtures() {
    }

    /**
     * Builds a real {@link Rfc9535FilterCompiler} backed by a mocked {@link JdbcClient} whose
     * {@code SELECT CAST(? AS jsonpath)} probe always succeeds — i.e. every expression that
     * doesn't compile under this pass's RFC 9535 subset falls back to "legacy valid".
     */
    public static Rfc9535FilterCompiler alwaysLegacyValid() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        JdbcClient.ResultQuerySpec resultQuerySpec = mock(JdbcClient.ResultQuerySpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(statementSpec);
        when(statementSpec.param(any())).thenReturn(statementSpec);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.query()).thenReturn(resultQuerySpec);
        when(resultQuerySpec.listOfRows()).thenReturn(java.util.List.of());

        return new Rfc9535FilterCompiler(
                new Rfc9535SqlPredicateCompiler(),
                new UnsupportedConstructDetector(),
                new JsonPathConverter(),
                jdbcClient,
                new DiscoveryProperties(),
                new FilterGrammarMetrics(new SimpleMeterRegistry()));
    }
}
