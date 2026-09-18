package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathConverter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Live-Postgres verification for the {@code Rfc9535FilterCompiler} statement-timeout hardening
 * fix (see docs/design/DESIGN-rfc9535-jsonpath-grammar.md, "Hardening additions", item 3).
 *
 * <p>This reproduces the exact defect-report methodology: a same-statement
 * {@code set_config('statement_timeout', ..., true)} bundled with the probed statement does
 * <b>not</b> bound that statement's own execution — Postgres arms {@code statement_timeout}
 * enforcement from the GUC value in effect when the statement starts, before its own target
 * list is evaluated. The fix instead uses the JDBC driver's own
 * {@link java.sql.Statement#setQueryTimeout}, applied via a dedicated {@link JdbcTemplate}
 * (exactly as {@code Rfc9535FilterCompiler#probeProcessed} constructs it), which is enforced by
 * the driver canceling the query — independent of statement evaluation order.</p>
 */
@Testcontainers
class Rfc9535ProbeStatementTimeoutIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("rfc9535_probe_timeout_test")
            .withUsername("test_user")
            .withPassword("test_password");

    private static HikariDataSource dataSource;

    @BeforeAll
    static void startContainerAndDataSource() {
        POSTGRES.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);
    }

    @AfterAll
    static void stopDataSourceAndContainer() {
        if (dataSource != null) {
            dataSource.close();
        }
        POSTGRES.stop();
    }

    /**
     * Reproduces the exact SQL-level defect this fix corrects: confirms that bundling
     * {@code set_config(...)} into the same statement as the probed query has NO effect on that
     * statement's own execution — against a real Postgres instance, matching the manual
     * verification that uncovered the original bug.
     */
    @Test
    void bundledSetConfigInSameStatement_doesNotEnforceTimeout() {
        JdbcTemplate plainTemplate = new JdbcTemplate(dataSource);

        Instant start = Instant.now();
        plainTemplate.queryForList(
                "SELECT set_config('statement_timeout', '1', true), pg_sleep(1.5)");
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(elapsed)
                .as("bundled set_config must NOT cancel the statement it's bundled with")
                .isGreaterThanOrEqualTo(Duration.ofMillis(1400));
    }

    /**
     * Verifies the actual fix: a dedicated {@link JdbcTemplate} with {@code setQueryTimeout}
     * applied — the exact mechanism now used by {@code Rfc9535FilterCompiler#probeProcessed} —
     * genuinely cancels a slow query within the configured bound, against a real Postgres
     * instance. Uses the same millisecond-to-seconds rounding
     * ({@code Math.max(1, (timeoutMs + 999) / 1000)}) as the production code.
     *
     * <p>Spring's {@code SQLStateSQLExceptionTranslator} classification of this cancellation is
     * not stable across Spring versions — it has surfaced as both
     * {@link DataAccessResourceFailureException} (a {@code NonTransientDataAccessException}
     * subtype) and {@code QueryTimeoutException}/other {@code TransientDataAccessException}
     * subtypes depending on the Spring Framework version. This is exactly why production code
     * ({@code Rfc9535FilterCompiler#probeProcessed}) never branches on the concrete exception
     * type alone: it checks the underlying {@link SQLException}'s SQLSTATE, {@code 57014}
     * (Postgres {@code query_canceled}), in addition to the ordinary
     * {@code TransientDataAccessException} check. This test asserts the same two signals rather
     * than pinning a specific exception subclass.</p>
     */
    @Test
    void jdbcLevelQueryTimeout_actuallyCancelsSlowQuery() {
        int timeoutMs = 300;
        JdbcTemplate probeTemplate = new JdbcTemplate(dataSource);
        probeTemplate.setQueryTimeout(Math.max(1, (timeoutMs + 999) / 1000));

        Instant start = Instant.now();
        assertThatThrownBy(() -> probeTemplate.queryForList("SELECT pg_sleep(5)"))
                .isInstanceOf(DataAccessException.class)
                .satisfies(thrown -> assertThat(
                                thrown instanceof TransientDataAccessException
                                        || "57014".equals(sqlStateOf(thrown)))
                        .as("cancellation must be a TransientDataAccessException or carry SQLSTATE 57014")
                        .isTrue());
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(elapsed)
                .as("JDBC-level queryTimeout must cancel the slow query near the configured bound, "
                        + "not let it run the full 5s")
                .isLessThan(Duration.ofSeconds(4));
    }

    /**
     * Walks {@code thrown}'s cause chain to find the underlying {@link SQLException}'s SQLSTATE —
     * mirrors {@code Rfc9535FilterCompiler#isQueryCanceled}, the mechanism under test.
     */
    private static String sqlStateOf(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }

    /**
     * Proves the actual behavior that matters (not just the exception class): a probe that times
     * out must propagate as a failure that Caffeine never caches, rather than being silently
     * cached as "this expression is invalid" for the remainder of the TTL. A single
     * {@link Rfc9535FilterCompiler} instance (one verdict cache) is used for both calls: the first
     * probe is forced to stall past the configured timeout (a real Postgres-side {@code pg_sleep}
     * spliced into the probe statement, so the JDBC driver's own {@code setQueryTimeout}
     * genuinely cancels it — the exact real-world trigger this hardening exists for), and the
     * second call — for the identical expression, now unstalled — must still succeed. If the
     * timeout had been wrongly cached as "invalid", the second call would fail too.
     */
    @Test
    void timeoutDuringProbe_isNotCachedAsRejection_andPropagates() {
        DiscoveryProperties properties = new DiscoveryProperties();
        properties.getFilterGrammar().setProbeStatementTimeoutMs(300);
        StatementStallingDataSource stallingDataSource =
                new StatementStallingDataSource(dataSource, Duration.ofSeconds(2));
        Rfc9535FilterCompiler compiler = new Rfc9535FilterCompiler(
                new Rfc9535SqlPredicateCompiler(),
                new UnsupportedConstructDetector(),
                new JsonPathConverter(),
                stallingDataSource,
                properties,
                new FilterGrammarMetrics(new SimpleMeterRegistry()));

        // Not valid RFC 9535 syntax, so the legacy probe path is taken — the exact path whose
        // outcome is (mis)cached.
        String expression = "$ ? (@.x == 1)";

        assertThatThrownBy(() -> compiler.compile(expression))
                .as("a probe cancellation must propagate, not be reported as an invalid expression")
                .isNotInstanceOf(InvalidRfc9535SyntaxException.class);

        assertThat(compiler.compile(expression))
                .as("the timeout must not have been cached — the retried, unstalled probe must "
                        + "still validate this genuinely valid legacy jsonpath expression")
                .isInstanceOf(LegacyCompiledFilter.class);
    }

    /**
     * A {@link DataSource} wrapper whose very first probe-shaped {@code prepareStatement} call is
     * rewritten to include a real Postgres-side {@code pg_sleep}, so the JDBC driver's
     * {@code setQueryTimeout} genuinely cancels it (SQLSTATE {@code 57014}) — every subsequent
     * call passes through to the real statement unmodified.
     */
    private static final class StatementStallingDataSource implements DataSource {
        private final DataSource delegate;
        private final Duration stallDuration;
        private final AtomicBoolean stallNextProbe = new AtomicBoolean(true);

        private StatementStallingDataSource(DataSource delegate, Duration stallDuration) {
            this.delegate = delegate;
            this.stallDuration = stallDuration;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(delegate.getConnection(username, password));
        }

        private Connection wrap(Connection real) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("prepareStatement".equals(method.getName())
                                && args != null && args.length > 0 && args[0] instanceof String sql
                                && sql.contains("CAST(? AS jsonpath)")
                                && stallNextProbe.getAndSet(false)) {
                            double stallSeconds = stallDuration.toMillis() / 1000.0;
                            return real.prepareStatement("SELECT pg_sleep(" + stallSeconds + "), CAST(? AS jsonpath)");
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }

    /**
     * End-to-end: {@link Rfc9535FilterCompiler}, wired against a real Postgres {@link DataSource}
     * (post-fix constructor), still correctly distinguishes a valid legacy jsonpath expression
     * from an invalid one via its legacy fallback probe — confirming the DataSource-based
     * rewiring did not regress the probe's actual parse-validation behavior.
     */
    @Test
    void legacyProbe_stillCorrectlyValidatesJsonpathSyntax_againstRealPostgres() {
        DiscoveryProperties properties = new DiscoveryProperties();
        Rfc9535FilterCompiler compiler = new Rfc9535FilterCompiler(
                new Rfc9535SqlPredicateCompiler(),
                new UnsupportedConstructDetector(),
                new JsonPathConverter(),
                dataSource,
                properties,
                new FilterGrammarMetrics(new SimpleMeterRegistry()));

        // Not valid RFC 9535 syntax (no brackets around the filter), but valid legacy
        // Postgres-jsonpath syntax -> falls back and the real Postgres probe accepts it.
        CompilationResult validLegacy = compiler.compile("$ ? (@.x == 1)");
        assertThat(validLegacy).isInstanceOf(LegacyCompiledFilter.class);

        // Invalid under both grammars -> the real Postgres probe rejects it too.
        assertThatThrownBy(() -> compiler.compile("$[??]"))
                .isInstanceOf(InvalidRfc9535SyntaxException.class);
    }
}
