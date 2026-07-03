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
         * Catalog-level {@code isActive} value-match filter. Resolved from the {@code ?active=}
         * HTTP query param (or the {@code discovery.filter.activeCatalog} config default) — NOT
         * the Beckn body. {@code null} ⇒ dimension not filtered; {@code TRUE} ⇒ only active
         * catalogs (active-or-absent per spec default); {@code FALSE} ⇒ only explicitly inactive
         * catalogs. Independent of {@link #networkId} and of {@link #validMatch}.
         */
        Boolean activeMatch,
        /**
         * Catalog-level {@code validity} value-match filter. Resolved from the {@code ?validity=}
         * HTTP query param (or the {@code discovery.filter.validCatalogs} config default).
         * {@code null} ⇒ dimension not filtered; {@code TRUE} ⇒ only catalogs currently within
         * their validity window (absent/open-ended/bare-time counts as valid); {@code FALSE} ⇒
         * only catalogs provably outside a parseable window. Independent of the other filters.
         */
        Boolean validMatch
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
    }

    /** Backward-compatible 7-arg constructor (rawSchemaContextUrls/networkId/active/valid default to empty/null). */
    public QueryRequest(String transactionId, String messageId, String filters,
                        List<DiscoverRequest.SpatialConstraint> spatial, String textSearch,
                        List<String> schemaTypes, List<String> schemaContextUrls) {
        this(transactionId, messageId, filters, spatial, textSearch,
                schemaTypes, schemaContextUrls, List.of(), null, null, null);
    }

    /** Backward-compatible 8-arg constructor (networkId/active/valid default to null). */
    public QueryRequest(String transactionId, String messageId, String filters,
                        List<DiscoverRequest.SpatialConstraint> spatial, String textSearch,
                        List<String> schemaTypes, List<String> schemaContextUrls,
                        List<String> rawSchemaContextUrls) {
        this(transactionId, messageId, filters, spatial, textSearch,
                schemaTypes, schemaContextUrls, rawSchemaContextUrls, null, null, null);
    }

    /** Backward-compatible 9-arg constructor (active/valid match default to null — no active/validity filtering). */
    public QueryRequest(String transactionId, String messageId, String filters,
                        List<DiscoverRequest.SpatialConstraint> spatial, String textSearch,
                        List<String> schemaTypes, List<String> schemaContextUrls,
                        List<String> rawSchemaContextUrls, String networkId) {
        this(transactionId, messageId, filters, spatial, textSearch,
                schemaTypes, schemaContextUrls, rawSchemaContextUrls, networkId, null, null);
    }

    // ── Factory ─────────────────────────────────────────────────────────────

    /**
     * Builds a {@code QueryRequest} from the raw HTTP request, extracting and
     * normalising all derived fields in one pass.
     *
     * @throws NullPointerException if {@code request} or its {@code context} is null
     */
    public static QueryRequest from(DiscoverRequest request) {
        return from(request, null, null);
    }

    /**
     * Builds a {@code QueryRequest} carrying the resolved {@code active}/{@code validity}
     * value-match flags. Both originate from HTTP query params (falling back to config
     * defaults) — not the Beckn body — so they are threaded in separately here rather than
     * read from {@code request}. A {@code null} means the corresponding dimension is not
     * filtered.
     *
     * @throws NullPointerException if {@code request} or its {@code context} is null
     */
    public static QueryRequest from(DiscoverRequest request, Boolean activeMatch, Boolean validMatch) {
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
                activeMatch,
                validMatch
        );
    }

    /** {@code true} when a network id is present to scope results by (#309). */
    public boolean hasNetworkFilter() {
        return networkId != null && !networkId.isBlank();
    }

    /**
     * {@code true} when the {@code active} value-match dimension should be filtered
     * ({@code activeMatch} is non-null). Independent of {@link #hasValidMatch()} and
     * {@link #hasNetworkFilter()} — the predicates compose but never gate each other.
     */
    public boolean hasActiveMatch() {
        return activeMatch != null;
    }

    /**
     * {@code true} when the {@code validity} value-match dimension should be filtered
     * ({@code validMatch} is non-null).
     */
    public boolean hasValidMatch() {
        return validMatch != null;
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
