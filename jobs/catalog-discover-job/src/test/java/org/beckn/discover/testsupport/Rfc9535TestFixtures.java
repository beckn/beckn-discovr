package org.beckn.discover.testsupport;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathConverter;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.FilterGrammarMetrics;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.Rfc9535FilterCompiler;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.Rfc9535SqlPredicateCompiler;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.UnsupportedConstructDetector;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

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
     * Builds a real {@link Rfc9535FilterCompiler} backed by a mocked {@link DataSource} whose
     * {@code SELECT CAST(? AS jsonpath)} probe always succeeds (empty result set, no exception)
     * — i.e. every expression that doesn't compile under this pass's RFC 9535 subset falls back
     * to "legacy valid".
     */
    public static Rfc9535FilterCompiler alwaysLegacyValid() {
        return new Rfc9535FilterCompiler(
                new Rfc9535SqlPredicateCompiler(),
                new UnsupportedConstructDetector(),
                new JsonPathConverter(),
                mockDataSourceForSuccessfulProbe(),
                new DiscoveryProperties(),
                new FilterGrammarMetrics(new SimpleMeterRegistry()));
    }

    /**
     * A {@link DataSource} whose {@code Connection}/{@code PreparedStatement}/{@code ResultSet}
     * chain always returns an empty result set — i.e. the legacy jsonpath cast probe always
     * succeeds, never touching a real database.
     */
    public static DataSource mockDataSourceForSuccessfulProbe() {
        try {
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            PreparedStatement preparedStatement = mock(PreparedStatement.class);
            ResultSet resultSet = mock(ResultSet.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
            when(preparedStatement.executeQuery()).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(false);
            return dataSource;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to build mock DataSource", e);
        }
    }
}
