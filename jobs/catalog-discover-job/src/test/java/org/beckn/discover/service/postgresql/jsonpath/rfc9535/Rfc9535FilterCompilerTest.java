package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Rfc9535FilterCompiler} — the dual-grammar orchestration seam.
 */
class Rfc9535FilterCompilerTest {

    private JdbcClient jdbcClient;
    private JdbcClient.StatementSpec stmt;
    private JdbcClient.ResultQuerySpec query;
    private DiscoveryProperties properties;
    private Rfc9535FilterCompiler compiler;

    @BeforeEach
    void setup() {
        jdbcClient = mock(JdbcClient.class);
        stmt = mock(JdbcClient.StatementSpec.class);
        query = mock(JdbcClient.ResultQuerySpec.class);
        when(jdbcClient.sql(anyString())).thenReturn(stmt);
        when(stmt.param(any())).thenReturn(stmt);
        when(stmt.query()).thenReturn(query);

        properties = new DiscoveryProperties();
        compiler = new Rfc9535FilterCompiler(
                new Rfc9535SqlPredicateCompiler(),
                new UnsupportedConstructDetector(),
                new JsonPathConverter(),
                jdbcClient,
                properties,
                new FilterGrammarMetrics(new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("valid RFC 9535 expression compiles to CompiledPredicate, never touches the DB")
    void validRfc9535_compilesWithoutDbProbe() {
        CompilationResult result = compiler.compile("$.offers[?(@.price < 100)]");
        assertThat(result).isInstanceOf(CompiledPredicate.class);
        verifyNoInteractions(jdbcClient);
    }

    @Test
    @DisplayName("invalid-under-RFC-9535-but-legacy-valid expression falls back, tagged legacy")
    void invalidRfc9535ButLegacyValid_fallsBack() {
        when(query.listOfRows()).thenReturn(java.util.List.of());
        CompilationResult result = compiler.compile("$ ? (@.x == 1)"); // not valid RFC 9535 (no brackets)
        assertThat(result).isInstanceOf(LegacyCompiledFilter.class);
    }

    @Test
    @DisplayName("invalid under both grammars throws InvalidRfc9535SyntaxException")
    void invalidUnderBothGrammars_throws() {
        when(query.listOfRows()).thenThrow(new InvalidDataAccessApiUsageException("syntax error"));
        assertThatThrownBy(() -> compiler.compile("$[??]"))
                .isInstanceOf(InvalidRfc9535SyntaxException.class);
    }

    @Test
    @DisplayName("denylisted construct (descendant) not valid under legacy either -> UnsupportedConstructException")
    void unsupportedConstruct_notLegacyValid_throws() {
        when(query.listOfRows()).thenThrow(new InvalidDataAccessApiUsageException("syntax error"));
        assertThatThrownBy(() -> compiler.compile("$..offers[?(@.price<1)]"))
                .isInstanceOf(UnsupportedConstructException.class)
                .satisfies(e -> assertThat(((UnsupportedConstructException) e).construct())
                        .isEqualTo(UnsupportedConstructException.UnsupportedConstruct.DESCENDANT_SEGMENT));
    }

    @Test
    @DisplayName("denylisted construct that IS legacy-valid falls back instead of NACK'ing (no regression)")
    void unsupportedConstruct_legacyValid_fallsBack() {
        when(query.listOfRows()).thenReturn(java.util.List.of());
        CompilationResult result = compiler.compile("$..offers[?(@.price<1)]");
        assertThat(result).isInstanceOf(LegacyCompiledFilter.class);
    }

    @Test
    @DisplayName("transient DB failure during legacy fallback propagates, never cached as a rejection")
    void transientDbFailure_propagates() {
        when(query.listOfRows()).thenThrow(new TransientDataAccessResourceException("connection reset"));
        assertThatThrownBy(() -> compiler.compile("$[??]"))
                .isInstanceOf(TransientDataAccessResourceException.class);
    }

    @Test
    @DisplayName("verdict is cached — repeat compiles of the same expression probe the DB once")
    void cacheReused_onlyOneProbe() {
        when(query.listOfRows()).thenThrow(new InvalidDataAccessApiUsageException("syntax error"));
        assertThatThrownBy(() -> compiler.compile("$[??]")).isInstanceOf(InvalidRfc9535SyntaxException.class);
        assertThatThrownBy(() -> compiler.compile("$[??]")).isInstanceOf(InvalidRfc9535SyntaxException.class);
        verify(jdbcClient, times(1)).sql(anyString());
    }

    @Test
    @DisplayName("rfc9535-enabled=false restores legacy-only behavior even for a valid RFC 9535 expression")
    void killSwitch_restoresLegacyOnlyBehavior() {
        properties.getFilterGrammar().setRfc9535Enabled(false);
        when(query.listOfRows()).thenReturn(java.util.List.of());

        CompilationResult result = compiler.compile("$.offers[?(@.price < 100)]");

        assertThat(result).isInstanceOf(LegacyCompiledFilter.class);
        verify(jdbcClient, times(1)).sql(anyString());
    }

    @Test
    @DisplayName("legacy-fallback-enabled=false surfaces the RFC 9535 failure directly, no DB probe")
    void legacyFallbackDisabled_noDbProbe() {
        properties.getFilterGrammar().setLegacyFallbackEnabled(false);
        assertThatThrownBy(() -> compiler.compile("$[??]"))
                .isInstanceOf(InvalidRfc9535SyntaxException.class);
        verifyNoInteractions(jdbcClient);
    }
}
