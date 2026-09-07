package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import java.util.List;
import java.util.Optional;

/**
 * Result of compiling an RFC 9535 filter expression to typed, parameterized SQL.
 *
 * <p>{@code whereFragment}/{@code whereParameters} feed
 * {@link org.beckn.discover.service.postgresql.QueryBuilderHelper.QueryTemplate#condition}
 * exactly like every other filter in this codebase. {@code offersProjectionFragment}
 * (present whenever the compiled filter targets fields reachable under {@code offers}) is a
 * correlated scalar-subquery SELECT-list expression producing the matched-offers array,
 * consumed by {@code PostgreSQLAssembler.mergeOffersFromRow()} the same way it consumes
 * today's {@code matching_offers} column.</p>
 */
public record CompiledPredicate(
        String whereFragment,
        List<Object> whereParameters,
        Optional<String> offersProjectionFragment,
        List<Object> projectionParameters,
        GrammarPath grammarPath
) implements CompilationResult {
    public CompiledPredicate {
        whereParameters = whereParameters != null ? List.copyOf(whereParameters) : List.of();
        projectionParameters = projectionParameters != null ? List.copyOf(projectionParameters) : List.of();
        offersProjectionFragment = offersProjectionFragment != null ? offersProjectionFragment : Optional.empty();
    }
}
