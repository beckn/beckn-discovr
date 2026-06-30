package org.beckn.discover.filter;

import org.beckn.discover.filter.rfc9535.Rfc9535PgTranslator;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathConverter;
import org.springframework.stereotype.Component;

/**
 * Single front-end chokepoint that compiles a filter expression of a declared
 * dialect ({@code filters.type}) into a PostgreSQL SQL/JSON path string.
 *
 * <p>This is the dialect router from the design:</p>
 * <ul>
 *   <li>{@code "jsonpath"} / absent → <b>legacy</b> PostgreSQL dialect via
 *       {@link JsonPathConverter#processFilter} — every existing client is
 *       unaffected.</li>
 *   <li>{@code "rfc9535"} → parse + translate via {@link Rfc9535PgTranslator}
 *       (RFC 9535 → PG). Throws {@link FilterParseException} /
 *       {@link UnsupportedFilterException} on bad/inexpressible input.</li>
 * </ul>
 *
 * <p>Used by both validation ({@code IntentQueryValidator}, pre-ACK) and the
 * query builders (async consumer) so the two stay in lockstep — one definition
 * of how each dialect becomes PG.</p>
 *
 * <p>Note: the PG translator is injected concretely for now (a single engine). A
 * {@code FilterTranslatorRegistry} keyed by engine replaces this when a second
 * datastore translator is added.</p>
 */
@Component
public class FilterCompiler {

    public static final String TYPE_JSONPATH = "jsonpath";
    public static final String TYPE_RFC9535 = "rfc9535";

    private final JsonPathConverter jsonPathConverter;
    private final Rfc9535PgTranslator rfc9535Translator;

    public FilterCompiler(JsonPathConverter jsonPathConverter, Rfc9535PgTranslator rfc9535Translator) {
        this.jsonPathConverter = jsonPathConverter;
        this.rfc9535Translator = rfc9535Translator;
    }

    /**
     * Compiles {@code expression} (of dialect {@code filterType}) into a PostgreSQL
     * SQL/JSON path string. Returns {@code ""} for a blank expression (callers treat
     * that as "match all"), matching the legacy behaviour.
     *
     * @throws FilterParseException       rfc9535 input that is not valid RFC 9535
     * @throws UnsupportedFilterException rfc9535 input PG cannot express
     */
    public String toPgJsonPath(String expression, String filterType) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        if (TYPE_RFC9535.equalsIgnoreCase(filterType)) {
            return rfc9535Translator.translate(expression).expression();
        }
        // null / "jsonpath" / anything else → legacy PG dialect (backward compatible).
        return jsonPathConverter.processFilter(expression);
    }
}
