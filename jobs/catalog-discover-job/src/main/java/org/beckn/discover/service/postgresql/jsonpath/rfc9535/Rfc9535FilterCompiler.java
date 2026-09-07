package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.beckn.discover.common.ErrorCodes;
import org.beckn.discover.common.ErrorMessages;
import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathConverter;
import org.noear.snack4.jsonpath.JsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

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
    private final JdbcClient jdbcClient;
    private final DiscoveryProperties discoveryProperties;
    private final FilterGrammarMetrics metrics;

    /** expression → compiled verdict (success or a cacheable, non-transient failure). */
    private final Cache<String, Verdict> verdictCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .build();

    public Rfc9535FilterCompiler(Rfc9535SqlPredicateCompiler sqlPredicateCompiler,
                                  UnsupportedConstructDetector unsupportedConstructDetector,
                                  JsonPathConverter jsonPathConverter,
                                  JdbcClient jdbcClient,
                                  DiscoveryProperties discoveryProperties,
                                  FilterGrammarMetrics metrics) {
        this.sqlPredicateCompiler = sqlPredicateCompiler;
        this.unsupportedConstructDetector = unsupportedConstructDetector;
        this.jsonPathConverter = jsonPathConverter;
        this.jdbcClient = jdbcClient;
        this.discoveryProperties = discoveryProperties;
        this.metrics = metrics;
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
        Verdict verdict = verdictCache.get(expression, discoveryProperties.getFilterGrammar().isRfc9535Enabled()
                ? this::doCompile
                : this::legacyOnly);
        if (verdict instanceof Verdict.Success success) {
            return success.result();
        }
        throw ((Verdict.Failure) verdict).exception();
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

    /** Parse-only probe against Postgres — no table access. Mirrors the pre-existing behavior. */
    private boolean probeProcessed(String processed) {
        try {
            jdbcClient.sql("SELECT CAST(? AS jsonpath)").param(processed).query().listOfRows();
            return true;
        } catch (NonTransientDataAccessException e) {
            return false; // genuine parse failure
        }
        // TransientDataAccessException (DB down / pool exhausted / timeout) propagates.
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
