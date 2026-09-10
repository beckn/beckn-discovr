package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathConverter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;

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
     */
    @Test
    void jdbcLevelQueryTimeout_actuallyCancelsSlowQuery() {
        int timeoutMs = 300;
        JdbcTemplate probeTemplate = new JdbcTemplate(dataSource);
        probeTemplate.setQueryTimeout(Math.max(1, (timeoutMs + 999) / 1000));

        Instant start = Instant.now();
        assertThatThrownBy(() -> probeTemplate.queryForList("SELECT pg_sleep(5)"))
                .isInstanceOf(TransientDataAccessException.class)
                .isInstanceOf(QueryTimeoutException.class);
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(elapsed)
                .as("JDBC-level queryTimeout must cancel the slow query near the configured bound, "
                        + "not let it run the full 5s")
                .isLessThan(Duration.ofSeconds(4));
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
