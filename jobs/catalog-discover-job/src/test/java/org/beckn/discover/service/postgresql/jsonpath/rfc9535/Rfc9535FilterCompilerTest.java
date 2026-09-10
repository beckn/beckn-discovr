package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.TransientDataAccessResourceException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Rfc9535FilterCompiler} — the dual-grammar orchestration seam.
 */
class Rfc9535FilterCompilerTest {

    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement preparedStatement;
    private ResultSet resultSet;
    private DiscoveryProperties properties;
    private Rfc9535FilterCompiler compiler;

    @BeforeEach
    void setup() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        preparedStatement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        properties = new DiscoveryProperties();
        compiler = new Rfc9535FilterCompiler(
                new Rfc9535SqlPredicateCompiler(),
                new UnsupportedConstructDetector(),
                new JsonPathConverter(),
                dataSource,
                properties,
                new FilterGrammarMetrics(new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("valid RFC 9535 expression compiles to CompiledPredicate, never touches the DB")
    void validRfc9535_compilesWithoutDbProbe() {
        CompilationResult result = compiler.compile("$.offers[?(@.price < 100)]");
        assertThat(result).isInstanceOf(CompiledPredicate.class);
        verifyNoInteractions(dataSource);
    }

    @Test
    @DisplayName("invalid-under-RFC-9535-but-legacy-valid expression falls back, tagged legacy")
    void invalidRfc9535ButLegacyValid_fallsBack() {
        CompilationResult result = compiler.compile("$ ? (@.x == 1)"); // not valid RFC 9535 (no brackets)
        assertThat(result).isInstanceOf(LegacyCompiledFilter.class);
    }

    @Test
    @DisplayName("invalid under both grammars throws InvalidRfc9535SyntaxException")
    void invalidUnderBothGrammars_throws() throws SQLException {
        when(preparedStatement.executeQuery()).thenThrow(new InvalidDataAccessApiUsageException("syntax error"));
        assertThatThrownBy(() -> compiler.compile("$[??]"))
                .isInstanceOf(InvalidRfc9535SyntaxException.class);
    }

    @Test
    @DisplayName("denylisted construct (descendant) not valid under legacy either -> UnsupportedConstructException")
    void unsupportedConstruct_notLegacyValid_throws() throws SQLException {
        when(preparedStatement.executeQuery()).thenThrow(new InvalidDataAccessApiUsageException("syntax error"));
        assertThatThrownBy(() -> compiler.compile("$..offers[?(@.price<1)]"))
                .isInstanceOf(UnsupportedConstructException.class)
                .satisfies(e -> assertThat(((UnsupportedConstructException) e).construct())
                        .isEqualTo(UnsupportedConstructException.UnsupportedConstruct.DESCENDANT_SEGMENT));
    }

    @Test
    @DisplayName("denylisted construct that IS legacy-valid falls back instead of NACK'ing (no regression)")
    void unsupportedConstruct_legacyValid_fallsBack() {
        CompilationResult result = compiler.compile("$..offers[?(@.price<1)]");
        assertThat(result).isInstanceOf(LegacyCompiledFilter.class);
    }

    @Test
    @DisplayName("transient DB failure during legacy fallback propagates, never cached as a rejection")
    void transientDbFailure_propagates() throws SQLException {
        when(preparedStatement.executeQuery()).thenThrow(new TransientDataAccessResourceException("connection reset"));
        assertThatThrownBy(() -> compiler.compile("$[??]"))
                .isInstanceOf(TransientDataAccessResourceException.class);
    }

    @Test
    @DisplayName("verdict is cached — repeat compiles of the same expression probe the DB once")
    void cacheReused_onlyOneProbe() throws SQLException {
        when(preparedStatement.executeQuery()).thenThrow(new InvalidDataAccessApiUsageException("syntax error"));
        assertThatThrownBy(() -> compiler.compile("$[??]")).isInstanceOf(InvalidRfc9535SyntaxException.class);
        assertThatThrownBy(() -> compiler.compile("$[??]")).isInstanceOf(InvalidRfc9535SyntaxException.class);
        verify(dataSource, times(1)).getConnection();
    }

    @Test
    @DisplayName("rfc9535-enabled=false restores legacy-only behavior even for a valid RFC 9535 expression")
    void killSwitch_restoresLegacyOnlyBehavior() throws SQLException {
        properties.getFilterGrammar().setRfc9535Enabled(false);

        CompilationResult result = compiler.compile("$.offers[?(@.price < 100)]");

        assertThat(result).isInstanceOf(LegacyCompiledFilter.class);
        verify(dataSource, times(1)).getConnection();
    }

    @Test
    @DisplayName("legacy-fallback-enabled=false surfaces the RFC 9535 failure directly, no DB probe")
    void legacyFallbackDisabled_noDbProbe() {
        properties.getFilterGrammar().setLegacyFallbackEnabled(false);
        assertThatThrownBy(() -> compiler.compile("$[??]"))
                .isInstanceOf(InvalidRfc9535SyntaxException.class);
        verifyNoInteractions(dataSource);
    }

    // ── Hardening fix 3: statement timeout on the legacy probe ──────────────

    @Test
    @DisplayName("legacy probe applies a JDBC-level query timeout (driver-enforced), derived from "
            + "the configured probe-statement-timeout-ms, rounded up to whole seconds")
    void legacyProbe_appliesJdbcLevelQueryTimeout() throws SQLException {
        properties.getFilterGrammar().setProbeStatementTimeoutMs(1500);

        compiler.compile("$ ? (@.x == 1)"); // not valid RFC 9535 -> falls back to legacy probe

        // 1500ms rounds up to 2 whole seconds — the JDBC API only supports integer-second timeouts.
        verify(preparedStatement).setQueryTimeout(2);
    }

    @Test
    @DisplayName("a sub-1000ms configured timeout is never rounded down to 0 (which JDBC treats as "
            + "'no timeout')")
    void legacyProbe_subSecondTimeout_neverRoundsToZero() throws SQLException {
        properties.getFilterGrammar().setProbeStatementTimeoutMs(200);

        compiler.compile("$ ? (@.x == 1)");

        verify(preparedStatement).setQueryTimeout(1);
    }

    // ── Hardening fix 1: maxLength cap ───────────────────────────────────────

    @Test
    @DisplayName("expression exceeding the configured max length is rejected before reaching the "
            + "parser or the legacy probe, never truncated or silently accepted")
    void oversizedExpression_rejectedWithoutParsingOrProbing() {
        properties.getFilterGrammar().setMaxExpressionLength(20);
        String oversized = "$.offers[?(@.price < " + "1".repeat(20) + ")]";
        assertThat(oversized.length()).isGreaterThan(20);

        assertThatThrownBy(() -> compiler.compile(oversized))
                .isInstanceOf(InvalidRfc9535SyntaxException.class);
        verifyNoInteractions(dataSource);
    }

    @Test
    @DisplayName("expression within the configured max length compiles normally")
    void expressionWithinMaxLength_compiles() {
        properties.getFilterGrammar().setMaxExpressionLength(4096);
        CompilationResult result = compiler.compile("$.offers[?(@.price < 100)]");
        assertThat(result).isInstanceOf(CompiledPredicate.class);
    }

    // ── Hardening fix 4: bounded verdict cache (TTL) ─────────────────────────

    @Test
    @DisplayName("verdict cache entries expire after the configured TTL, forcing a fresh probe "
            + "instead of growing unboundedly stale")
    void verdictCacheEntry_expiresAfterConfiguredTtl() throws SQLException {
        properties.getFilterGrammar().setVerdictCacheTtlMinutes(1);
        FakeTicker fakeTicker = new FakeTicker();
        compiler = new Rfc9535FilterCompiler(
                new Rfc9535SqlPredicateCompiler(),
                new UnsupportedConstructDetector(),
                new JsonPathConverter(),
                dataSource,
                properties,
                new FilterGrammarMetrics(new SimpleMeterRegistry()),
                fakeTicker);
        when(preparedStatement.executeQuery()).thenThrow(new InvalidDataAccessApiUsageException("syntax error"));

        assertThatThrownBy(() -> compiler.compile("$[??]")).isInstanceOf(InvalidRfc9535SyntaxException.class);
        verify(dataSource, times(1)).getConnection();

        // Still within TTL: served from cache, no second probe.
        assertThatThrownBy(() -> compiler.compile("$[??]")).isInstanceOf(InvalidRfc9535SyntaxException.class);
        verify(dataSource, times(1)).getConnection();

        // Advance the fake clock past the configured 1-minute TTL — proves TTL, not size, bounds
        // cache-miss rate over time (maximumSize is 10_000, far larger than one entry).
        fakeTicker.advance(java.time.Duration.ofMinutes(2));
        assertThatThrownBy(() -> compiler.compile("$[??]")).isInstanceOf(InvalidRfc9535SyntaxException.class);
        verify(dataSource, times(2)).getConnection();
    }

    /** Manually-advanced {@link com.github.benmanes.caffeine.cache.Ticker} for deterministic,
     *  sleep-free TTL expiry assertions. */
    private static final class FakeTicker implements com.github.benmanes.caffeine.cache.Ticker {
        private long nanos;

        void advance(java.time.Duration duration) {
            nanos += duration.toNanos();
        }

        @Override
        public long read() {
            return nanos;
        }
    }

    @Test
    @DisplayName("a burst of distinct expressions bounds cache growth via maximumSize, each a "
            + "separate probe — no unbounded memory growth")
    void burstOfDistinctExpressions_eachProbedIndependently() throws SQLException {
        when(preparedStatement.executeQuery()).thenThrow(new InvalidDataAccessApiUsageException("syntax error"));
        for (int i = 0; i < 50; i++) {
            String expression = "$[?? " + i + "]";
            assertThatThrownBy(() -> compiler.compile(expression))
                    .isInstanceOf(InvalidRfc9535SyntaxException.class);
        }
        verify(dataSource, times(50)).getConnection();
    }
}
