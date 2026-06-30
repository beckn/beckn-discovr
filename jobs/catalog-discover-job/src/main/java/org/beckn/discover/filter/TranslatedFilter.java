package org.beckn.discover.filter;

/**
 * Result of translating an RFC 9535 JSONPath expression into a target engine's
 * query dialect.
 *
 * @param expression    the engine-specific query fragment (e.g. a PostgreSQL
 *                      SQL/JSON path string)
 * @param selectionPath {@code true} when the expression selects nodes (and can
 *                      therefore feed a projection such as
 *                      {@code jsonb_path_query_array}); {@code false} for a pure
 *                      boolean predicate
 */
public record TranslatedFilter(String expression, boolean selectionPath) {
}
