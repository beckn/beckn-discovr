package org.beckn.discover.filter.rfc9535;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.beckn.discover.filter.FilterParseException;
import org.beckn.discover.filter.FilterTranslator;
import org.beckn.discover.filter.TranslatedFilter;
import org.beckn.discover.filter.rfc9535.gen.JsonPathLexer;
import org.beckn.discover.filter.rfc9535.gen.JsonPathParser;
import org.springframework.stereotype.Component;

/**
 * {@link FilterTranslator} for PostgreSQL: parses an RFC 9535 JSONPath expression
 * against the shared ANTLR grammar and emits an equivalent PostgreSQL SQL/JSON
 * path string via {@link PgJsonPathEmitter}.
 *
 * <p><b>Performance:</b> translation is a pure function of the input string, so
 * results are memoised in a bounded Caffeine cache — the first sighting parses;
 * every repeat is a map lookup. This mirrors the existing
 * {@code IntentQueryValidator} cache and keeps the hot path allocation-free for
 * the common case of repeated expressions.</p>
 */
@Component
public class Rfc9535PgTranslator implements FilterTranslator {

    private final Cache<String, TranslatedFilter> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .build();

    @Override
    public String engine() {
        return "postgresql";
    }

    @Override
    public TranslatedFilter translate(String rfc9535Expression) {
        if (rfc9535Expression == null || rfc9535Expression.isBlank()) {
            throw new FilterParseException("empty filter expression");
        }
        return cache.get(rfc9535Expression.trim(), this::compile);
    }

    private TranslatedFilter compile(String expression) {
        JsonPathLexer lexer = new JsonPathLexer(CharStreams.fromString(expression));
        lexer.removeErrorListeners();
        lexer.addErrorListener(ThrowingErrorListener.INSTANCE);

        JsonPathParser parser = new JsonPathParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(ThrowingErrorListener.INSTANCE);

        JsonPathParser.JsonpathContext tree;
        try {
            tree = parser.jsonpath();
        } catch (ParseCancellationException e) {
            throw new FilterParseException("Invalid RFC 9535 JSONPath: " + e.getMessage(), e);
        }

        String pg = new PgJsonPathEmitter().visit(tree);
        // Output always begins with '$' (a node query), so downstream treats it as
        // a selection path and wraps it in exists(...) for the WHERE predicate.
        return new TranslatedFilter(pg, true);
    }
}
