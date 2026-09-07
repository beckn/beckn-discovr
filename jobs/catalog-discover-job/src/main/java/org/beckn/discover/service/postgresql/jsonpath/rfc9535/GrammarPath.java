package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

/**
 * Which grammar a {@code message.intent.filters.expression} was compiled against.
 * Used for metrics/log tagging during the RFC 9535 migration — never surfaced to clients.
 */
public enum GrammarPath {
    RFC_9535,
    LEGACY_POSTGRES
}
