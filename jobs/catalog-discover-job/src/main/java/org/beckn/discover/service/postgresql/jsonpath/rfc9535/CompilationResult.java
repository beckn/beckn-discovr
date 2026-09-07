package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

/**
 * Outcome of {@link Rfc9535FilterCompiler#compile(String)} — either grammar succeeded.
 */
public sealed interface CompilationResult permits CompiledPredicate, LegacyCompiledFilter {
}
