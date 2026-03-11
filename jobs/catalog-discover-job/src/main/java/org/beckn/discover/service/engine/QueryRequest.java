package org.beckn.discover.service.engine;

import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.util.DiscoveryServiceUtil;

import java.util.List;
import java.util.Objects;

/**
 * Immutable value object that carries all query parameters normalised from a
 * {@link DiscoverRequest}.
 *
 * <p>Decouples every engine and assembler implementation from the HTTP/web
 * layer model — none of them need to import {@code DiscoverRequest} or
 * {@code Context}.  The schema type and context-URL lists are pre-extracted
 * once here so callers never repeat the extraction logic.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * QueryRequest qr = QueryRequest.from(discoverRequest);
 * if (qr.hasFilters() && qr.hasSpatial()) {
 *     Optional<List<Catalog>> result = queryEngine.executeCombinedQuery(qr);
 * }
 * }</pre>
 */
public record QueryRequest(
        String transactionId,
        String messageId,
        String filters,
        List<DiscoverRequest.SpatialConstraint> spatial,
        String textSearch,
        List<String> schemaTypes,
        List<String> schemaContextUrls
) {

    /**
     * Compact canonical constructor — enforces immutability.
     *
     * <p>Note: {@code transactionId} is allowed to be {@code null} because
     * the Beckn schema marks it optional. Downstream components use it only
     * for logging, so a {@code null} is safe.</p>
     */
    public QueryRequest {
        // Defensive unmodifiable views; callers may not assume mutability
        spatial           = spatial           != null ? List.copyOf(spatial)           : List.of();
        schemaTypes       = schemaTypes       != null ? List.copyOf(schemaTypes)       : List.of();
        schemaContextUrls = schemaContextUrls != null ? List.copyOf(schemaContextUrls) : List.of();
    }

    // ── Factory ─────────────────────────────────────────────────────────────

    /**
     * Builds a {@code QueryRequest} from the raw HTTP request, extracting and
     * normalising all derived fields in one pass.
     *
     * @throws NullPointerException if {@code request} or its {@code context} is null
     */
    public static QueryRequest from(DiscoverRequest request) {
        Objects.requireNonNull(request, "DiscoverRequest must not be null");
        Objects.requireNonNull(request.getContext(), "DiscoverRequest.context must not be null");

        DiscoveryServiceUtil.SchemaContextParts parts =
                DiscoveryServiceUtil.extractSchemaContextParts(request.getContext());

        return new QueryRequest(
                request.getContext().getTransactionId(),
                request.getContext().getMessageId(),
                request.getFilters(),
                request.getSpatial(),
                request.getTextSearch(),
                parts.types(),
                parts.urls()
        );
    }

    // ── Convenience predicates ───────────────────────────────────────────────

    /** {@code true} when a JSONPath filter expression is present and non-blank. */
    public boolean hasFilters() {
        return filters != null && !filters.isBlank();
    }

    /** {@code true} when at least one spatial constraint is present. */
    public boolean hasSpatial() {
        return !spatial.isEmpty();
    }

    /** {@code true} when a natural-language / full-text search string is present. */
    public boolean hasTextSearch() {
        return textSearch != null && !textSearch.isBlank();
    }

    /** {@code true} when schema type filtering is requested. */
    public boolean hasSchemaFilters() {
        return !schemaTypes.isEmpty() || !schemaContextUrls.isEmpty();
    }
}
