package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

/**
 * Result of falling back to the legacy Postgres-jsonpath probe. Shape unchanged from what
 * {@code JsonPathConverter.processFilter()} produces today — the legacy path is untouched.
 */
public record LegacyCompiledFilter(String postgresJsonpath) implements CompilationResult {
}
