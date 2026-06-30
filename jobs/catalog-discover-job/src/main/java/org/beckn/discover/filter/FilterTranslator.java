package org.beckn.discover.filter;

/**
 * Translates an RFC 9535 JSONPath expression into a specific database engine's
 * query dialect. This is the pluggable seam that keeps the database from being a
 * hard constraint: a new datastore (Elasticsearch, Cassandra, …) is added by
 * registering a new {@code FilterTranslator} bean — nothing else changes.
 *
 * <p>Implementations parse the expression against the shared RFC 9535 grammar
 * (the canonical, DB-neutral representation) and emit their own query form.</p>
 *
 * <p>Two failure modes:</p>
 * <ul>
 *   <li>{@link FilterParseException} — the input is not valid RFC 9535.</li>
 *   <li>{@link UnsupportedFilterException} — valid RFC 9535 that this engine
 *       cannot express (the capability gate).</li>
 * </ul>
 */
public interface FilterTranslator {

    /** Target engine id, e.g. {@code "postgresql"}, {@code "elasticsearch"}. */
    String engine();

    /**
     * Parses and translates an RFC 9535 expression into this engine's dialect.
     *
     * @throws FilterParseException       when the expression is not valid RFC 9535
     * @throws UnsupportedFilterException when the expression is valid but
     *                                    inexpressible on this engine
     */
    TranslatedFilter translate(String rfc9535Expression);
}
