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
        List<String> schemaContextUrls,
        /** Raw schemaContext URLs preserving fragment pairing (e.g. "https://schema.org#GroceryItem"). */
        List<String> rawSchemaContextUrls,
        /**
         * Requesting network id (from {@code context.networkId}). Used to scope query
         * results to catalogs published to this network (#309). May be {@code null}/blank,
         * in which case no network filter is applied (results are network-agnostic).
         */
        String networkId,
        /**
         * Filter dialect (from {@code message.intent.filters.type}): {@code "jsonpath"}
         * (legacy PostgreSQL SQL/JSON path) or {@code "rfc9535"} (standards-compliant,
         * translated to PG). Null/blank is normalised to {@code "jsonpath"} so existing
         * callers stay on the legacy path.
         */
        String filterType
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
        spatial              = spatial              != null ? List.copyOf(spatial)              : List.of();
        schemaTypes          = schemaTypes          != null ? List.copyOf(schemaTypes)          : List.of();
        schemaContextUrls    = schemaContextUrls    != null ? List.copyOf(schemaContextUrls)    : List.of();
        rawSchemaContextUrls = rawSchemaContextUrls != null ? List.copyOf(rawSchemaContextUrls) : List.of();
        filterType           = (filterType == null || filterType.isBlank()) ? "jsonpath" : filterType;
    }

    /** Backward-compatible 7-arg constructor (rawSchemaContextUrls + networkId default to empty/null). */
    public QueryRequest(String transactionId, String messageId, String filters,
                        List<DiscoverRequest.SpatialConstraint> spatial, String textSearch,
                        List<String> schemaTypes, List<String> schemaContextUrls) {
        this(transactionId, messageId, filters, spatial, textSearch,
                schemaTypes, schemaContextUrls, List.of(), null, "jsonpath");
    }

    /** Backward-compatible 8-arg constructor (networkId defaults to null — no network scoping). */
    public QueryRequest(String transactionId, String messageId, String filters,
                        List<DiscoverRequest.SpatialConstraint> spatial, String textSearch,
                        List<String> schemaTypes, List<String> schemaContextUrls,
                        List<String> rawSchemaContextUrls) {
        this(transactionId, messageId, filters, spatial, textSearch,
                schemaTypes, schemaContextUrls, rawSchemaContextUrls, null, "jsonpath");
    }

    /** Backward-compatible 9-arg constructor (filterType defaults to legacy "jsonpath"). */
    public QueryRequest(String transactionId, String messageId, String filters,
                        List<DiscoverRequest.SpatialConstraint> spatial, String textSearch,
                        List<String> schemaTypes, List<String> schemaContextUrls,
                        List<String> rawSchemaContextUrls, String networkId) {
        this(transactionId, messageId, filters, spatial, textSearch,
                schemaTypes, schemaContextUrls, rawSchemaContextUrls, networkId, "jsonpath");
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

        // V2.0: schemaContext belongs in message.intent, not context.
        // Read from intent first; fall back to context for backward compatibility.
        List<String> schemaContextUrls = resolveSchemaContext(request);
        DiscoveryServiceUtil.SchemaContextParts parts =
                DiscoveryServiceUtil.extractSchemaContextParts(schemaContextUrls);

        return new QueryRequest(
                request.getContext().getTransactionId(),
                request.getContext().getMessageId(),
                request.getFilters(),
                request.getSpatial(),
                request.getTextSearch(),
                parts.types(),
                parts.urls(),
                schemaContextUrls,
                request.getContext().getNetworkId(),
                request.getFilterType()
        );
    }

    /** {@code true} when a network id is present to scope results by (#309). */
    public boolean hasNetworkFilter() {
        return networkId != null && !networkId.isBlank();
    }

    private static List<String> resolveSchemaContext(DiscoverRequest request) {
        if (request.getMessage() != null
                && request.getMessage().getIntent() != null
                && request.getMessage().getIntent().getSchemaContext() != null
                && !request.getMessage().getIntent().getSchemaContext().isEmpty()) {
            return request.getMessage().getIntent().getSchemaContext();
        }
        // Fallback: legacy location on context (will be empty for pure V2.0 requests)
        if (request.getContext().getSchemaContext() != null) {
            return request.getContext().getSchemaContext();
        }
        return List.of();
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
