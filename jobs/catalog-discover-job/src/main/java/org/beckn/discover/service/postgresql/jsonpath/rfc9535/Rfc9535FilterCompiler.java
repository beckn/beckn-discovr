package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.beckn.discover.common.ErrorCodes;
import org.beckn.discover.common.ErrorMessages;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathConverter;
import org.noear.snack4.jsonpath.JsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Duration;

import static net.logstash.logback.argument.StructuredArguments.value;

/**
 * Orchestration seam for the RFC 9535 → legacy Postgres-jsonpath dual-grammar migration (see
 * docs/design/DESIGN-rfc9535-jsonpath-grammar.md "Files to create").
 *
 * <p>Tries RFC 9535 first. Falls back to the legacy Postgres-jsonpath probe — the unmodified
 * path {@code JsonPathQueryBuilder} already ran before this feature existed — on either (a) the
 * expression failing to parse as RFC 9535 at all, or (b) it parsing but hitting an
 * {@link UnsupportedConstructException} (e.g. descendant {@code ..}, valid RFC 9535 syntax a
 * legacy client may already send and rely on). Only if the legacy probe also fails does the
 * RFC 9535-side failure surface as a NACK — so a construct denylisted under RFC 9535 but valid
 * legacy syntax keeps working, unmodified, via the fallback.</p>
 *
 * <p>Owns the Caffeine verdict cache (moved here from {@code IntentQueryValidator}, which is now
 * a thin caller) keyed on the <b>raw</b> expression. Compilation is a pure function of the
 * expression string, so negative verdicts are cached too — a client spamming the same invalid
 * expression cannot repeatedly re-parse or re-probe. Transient DB errors from the legacy probe
 * are never cached (Caffeine does not cache when the loading function throws) and propagate as
 * 5xx via the caller.</p>
 */
@Service
public class Rfc9535FilterCompiler {

    private static final Logger log = LoggerFactory.getLogger(Rfc9535FilterCompiler.class);

    private final Rfc9535SqlPredicateCompiler sqlPredicateCompiler;
    private final UnsupportedConstructDetector unsupportedConstructDetector;
    private final JsonPathConverter jsonPathConverter;
    private final DataSource dataSource;
    private final DiscoveryProperties discoveryProperties;
    private final FilterGrammarMetrics metrics;

    /**
     * expression → compiled verdict (success or a cacheable, non-transient failure).
     *
     * <p>{@code expireAfterWrite} bounds the rate of cache *misses* (and therefore legacy-probe
     * round-trips) a flood of distinct expressions can force — {@code maximumSize} alone only
     * bounds memory, not the miss rate over time.</p>
     */
    private final Cache<String, Verdict> verdictCache;

    @Autowired
    public Rfc9535FilterCompiler(Rfc9535SqlPredicateCompiler sqlPredicateCompiler,
                                  UnsupportedConstructDetector unsupportedConstructDetector,
                                  JsonPathConverter jsonPathConverter,
                                  DataSource dataSource,
                                  DiscoveryProperties discoveryProperties,
                                  FilterGrammarMetrics metrics) {
        this(sqlPredicateCompiler, unsupportedConstructDetector, jsonPathConverter, dataSource,
                discoveryProperties, metrics, Ticker.systemTicker());
    }

    /**
     * Test-only seam: lets {@code verdictCache}'s TTL be driven by a fake {@link Ticker} instead
     * of wall-clock time, so cache-expiry behavior can be asserted deterministically without
     * {@code Thread.sleep()}.
     */
    Rfc9535FilterCompiler(Rfc9535SqlPredicateCompiler sqlPredicateCompiler,
                          UnsupportedConstructDetector unsupportedConstructDetector,
                          JsonPathConverter jsonPathConverter,
                          DataSource dataSource,
                          DiscoveryProperties discoveryProperties,
                          FilterGrammarMetrics metrics,
                          Ticker ticker) {
        this.sqlPredicateCompiler = sqlPredicateCompiler;
        this.unsupportedConstructDetector = unsupportedConstructDetector;
        this.jsonPathConverter = jsonPathConverter;
        this.dataSource = dataSource;
        this.discoveryProperties = discoveryProperties;
        this.metrics = metrics;
        this.verdictCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(discoveryProperties.getFilterGrammar().getVerdictCacheTtlMinutes()))
                .ticker(ticker)
                .build();
    }

    /**
     * Compiles {@code expression} into either a {@link CompiledPredicate} (RFC 9535) or a
     * {@link LegacyCompiledFilter} (legacy fallback).
     *
     * @throws InvalidRfc9535SyntaxException invalid under both grammars
     * @throws UnsupportedConstructException valid RFC 9535, denylisted construct, and not valid
     *                                        legacy syntax either
     */
    public CompilationResult compile(String expression) {
        rejectIfOversized(expression);
        Verdict verdict = verdictCache.get(expression, discoveryProperties.getFilterGrammar().isRfc9535Enabled()
                ? this::doCompile
                : this::legacyOnly);
        if (verdict instanceof Verdict.Success success) {
            return success.result();
        }
        throw ((Verdict.Failure) verdict).exception();
    }

    /**
     * Rejects an oversized expression before it reaches the RFC 9535 parser, the legacy Postgres
     * probe, or the verdict cache (as a cache key). Checked eagerly, outside the cache, so a
     * flood of distinct oversized strings cannot itself grow the cache.
     */
    private void rejectIfOversized(String expression) {
        int maxLength = discoveryProperties.getFilterGrammar().getMaxExpressionLength();
        if (expression.length() > maxLength) {
            metrics.recordRejected("EXPRESSION_TOO_LONG");
            log.warn(LogEvent.FILTER_GRAMMAR_REJECTED,
                    value("expressionLength", expression.length()), value("maxLength", maxLength));
            throw new InvalidRfc9535SyntaxException(
                    "Filter expression exceeds max supported length (" + maxLength + " characters)", null);
        }
    }

    private Verdict doCompile(String expression) {
        try {
            JsonPath parsed = JsonPath.parse(expression);
            var unsupported = unsupportedConstructDetector.firstUnsupported(parsed.getSegments());
            if (unsupported.isPresent()) {
                return fallbackOrFail(expression, new UnsupportedConstructException(
                        unsupported.get(), constructMessage(unsupported.get())));
            }
            CompiledPredicate predicate = sqlPredicateCompiler.toSqlPredicate(parsed.getSegments());
            metrics.recordRfc9535();
            log.debug(LogEvent.FILTER_GRAMMAR_RFC9535, value("expression", expression));
            return new Verdict.Success(predicate);
        } catch (UnsupportedConstructException e) {
            return fallbackOrFail(expression, e);
        } catch (RuntimeException e) {
            // Covers snack4-jsonpath's JsonPathException and this compiler's own
            // InvalidRfc9535SyntaxException — both mean "not valid RFC 9535 syntax".
            return fallbackOrFail(expression,
                    new InvalidRfc9535SyntaxException("Expression is not valid RFC 9535 syntax", e));
        }
    }

    /**
     * Tries the legacy Postgres-jsonpath probe. A {@link NonTransientDataAccessException} means
     * the legacy grammar rejects it too, so the original RFC 9535-side failure is the one that's
     * surfaced. A transient DB error propagates uncaught so Caffeine never caches this outcome.
     */
    private Verdict fallbackOrFail(String expression, RuntimeException rfc9535Failure) {
        if (discoveryProperties.getFilterGrammar().isLegacyFallbackEnabled() && probeLegacy(expression)) {
            metrics.recordLegacy();
            log.info(LogEvent.FILTER_GRAMMAR_LEGACY, value("expression", expression));
            return new Verdict.Success(new LegacyCompiledFilter(jsonPathConverter.processFilter(expression)));
        }
        String reason = rfc9535Failure instanceof UnsupportedConstructException uce
                ? uce.construct().name()
                : "INVALID_SYNTAX";
        metrics.recordRejected(reason);
        log.warn(LogEvent.FILTER_GRAMMAR_REJECTED, value("expression", expression), value("reason", reason));
        return new Verdict.Failure(rfc9535Failure);
    }

    private Verdict legacyOnly(String expression) {
        String processed = jsonPathConverter.processFilter(expression);
        if (probeProcessed(processed)) {
            metrics.recordLegacy();
            log.info(LogEvent.FILTER_GRAMMAR_LEGACY, value("expression", expression));
            return new Verdict.Success(new LegacyCompiledFilter(processed));
        }
        metrics.recordRejected("INVALID_SYNTAX");
        log.warn(LogEvent.FILTER_GRAMMAR_REJECTED, value("expression", expression));
        return new Verdict.Failure(new InvalidRfc9535SyntaxException(
                "Expression is not a valid Postgres jsonpath expression", null));
    }

    private boolean probeLegacy(String expression) {
        return probeProcessed(jsonPathConverter.processFilter(expression));
    }

    /**
     * Parse-only probe against Postgres — no table access. Mirrors the pre-existing behavior.
     *
     * <p>The timeout is enforced via the JDBC driver's own {@link java.sql.Statement#setQueryTimeout}
     * mechanism (set on a dedicated {@link JdbcTemplate} built for this single probe), not by a
     * Postgres-side {@code set_config('statement_timeout', ...)} bundled into the same statement
     * as the probe itself — Postgres arms {@code statement_timeout} enforcement from the GUC value
     * in effect at the *start* of statement processing, before that statement's own target list is
     * evaluated, so a {@code set_config} call inside the statement being timed has no effect on
     * that statement's execution. The driver-level timeout has no such ordering pitfall and cannot
     * leak session state onto the pooled connection's next use, since it is enforced by the driver
     * canceling the query rather than by mutating a session GUC. A fresh, single-use
     * {@code JdbcTemplate} is built per probe (cheap — it only wraps the shared {@link DataSource}
     * reference) so this tight timeout applies to this probe alone and never affects the shared
     * {@code JdbcClient} bean used by every other query in this job.</p>
     */
    private boolean probeProcessed(String processed) {
        int timeoutMs = discoveryProperties.getFilterGrammar().getProbeStatementTimeoutMs();
        JdbcTemplate probeTemplate = new JdbcTemplate(dataSource);
        probeTemplate.setQueryTimeout(Math.max(1, (timeoutMs + 999) / 1000));
        try {
            probeTemplate.queryForList("SELECT CAST(? AS jsonpath)", processed);
            return true;
        } catch (NonTransientDataAccessException e) {
            return false; // genuine parse failure
        }
        // TransientDataAccessException (DB down / pool exhausted / probe timeout) propagates.
    }

    private static String constructMessage(UnsupportedConstructException.UnsupportedConstruct construct) {
        return switch (construct) {
            case DESCENDANT_SEGMENT -> ErrorMessages.SCH_UNSUPPORTED_JSONPATH_DESCENDANT;
            case SLICE_WITH_STEP -> ErrorMessages.SCH_UNSUPPORTED_JSONPATH_SLICE_STEP;
            case COUNT_FUNCTION -> ErrorMessages.SCH_UNSUPPORTED_JSONPATH_COUNT_FUNCTION;
            case VALUE_FUNCTION -> ErrorMessages.SCH_UNSUPPORTED_JSONPATH_VALUE_FUNCTION;
            case REGEX_FUNCTION -> ErrorMessages.SCH_UNSUPPORTED_JSONPATH_REGEX_FUNCTION;
        };
    }

    /** Maps an {@link UnsupportedConstructException.UnsupportedConstruct} to its NACK error code. */
    public static String errorCodeFor(UnsupportedConstructException.UnsupportedConstruct construct) {
        return switch (construct) {
            case DESCENDANT_SEGMENT -> ErrorCodes.SCH_UNSUPPORTED_JSONPATH_DESCENDANT;
            case SLICE_WITH_STEP -> ErrorCodes.SCH_UNSUPPORTED_JSONPATH_SLICE_STEP;
            case COUNT_FUNCTION -> ErrorCodes.SCH_UNSUPPORTED_JSONPATH_COUNT_FUNCTION;
            case VALUE_FUNCTION -> ErrorCodes.SCH_UNSUPPORTED_JSONPATH_VALUE_FUNCTION;
            case REGEX_FUNCTION -> ErrorCodes.SCH_UNSUPPORTED_JSONPATH_REGEX_FUNCTION;
        };
    }

    /** Internal cache payload — a successful compilation, or a cacheable, non-transient failure. */
    private sealed interface Verdict permits Verdict.Success, Verdict.Failure {
        record Success(CompilationResult result) implements Verdict {
        }

        record Failure(RuntimeException exception) implements Verdict {
        }
    }
}
