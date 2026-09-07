package org.beckn.discover.service.postgresql.jsonpath;

import org.beckn.discover.service.postgresql.QueryBuilderHelper;
import org.beckn.discover.service.postgresql.QueryBuilderHelper.QuerySpec;
import org.beckn.discover.service.postgresql.QueryBuilderHelper.QueryTemplate;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.CompilationResult;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.CompiledPredicate;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.LegacyCompiledFilter;
import org.beckn.discover.service.postgresql.jsonpath.rfc9535.Rfc9535FilterCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Builds JSONPath-based PostgreSQL queries.
 *
 * <p>Branches on which grammar {@link Rfc9535FilterCompiler} resolved the filter to (see
 * docs/design/DESIGN-rfc9535-jsonpath-grammar.md):
 * <ul>
 *   <li>{@link CompiledPredicate} (RFC 9535) — the compiled typed {@code jsonb}-operator WHERE
 *       fragment feeds {@link QueryTemplate#condition}; an offers-projection fragment (present
 *       whenever the filter targets fields under {@code offers}) feeds
 *       {@link QueryTemplate#projectionColumn}.</li>
 *   <li>{@link LegacyCompiledFilter} (legacy Postgres jsonpath) — completely unchanged flow:
 *       {@code exists($ ? (...))} wrapping, {@link QueryBuilderHelper#JSONPATH_MATCH}, and
 *       {@link QueryBuilderHelper#BASE_SELECT_WITH_FILTER_RESULT} for selection-path filters.</li>
 * </ul>
 * </p>
 */
@Component
public class JsonPathQueryBuilder {

    private static final Logger log = LoggerFactory.getLogger(JsonPathQueryBuilder.class);

    private final Rfc9535FilterCompiler filterCompiler;

    public JsonPathQueryBuilder(Rfc9535FilterCompiler filterCompiler) {
        this.filterCompiler = filterCompiler;
    }

    /**
     * Builds a complete JSONPath query (SQL + params) with optional schema filters and limit.
     * When filter is a selection path (starts with $), always adds filter-result column so
     * response can show only matched offers/items regardless of expression format.
     */
    /** Network-agnostic overload (no {@code networkId} scoping) — for tests / non-network callers. */
    public QuerySpec build(String filters, List<String> rawSchemaContextUrls, int limit) {
        return build(filters, rawSchemaContextUrls, limit, null);
    }

    /** Network-scoped overload without active/validity filtering (delegates with both matches null). */
    public QuerySpec build(String filters, List<String> rawSchemaContextUrls, int limit, String networkId) {
        return build(filters, rawSchemaContextUrls, limit, networkId, null, null, null);
    }

    public QuerySpec build(String filters, List<String> rawSchemaContextUrls, int limit,
                           String networkId, Boolean activeMatch, Boolean validMatch, Instant now) {
        QueryTemplate template = baseTemplate(filterCompiler.compile(filters));
        QuerySpec query = template
                .schemaFiltersPaired(rawSchemaContextUrls)
                .networkFilter(networkId)
                .activeFilter(activeMatch)
                .validityFilter(validMatch, now)
                .build(limit);
        log.debug("Built JSONPath query with {} parameters, limit {}, activeMatch {}, validMatch {}",
                query.parameters().size(), limit, activeMatch, validMatch);
        return query;
    }

    /**
     * Builds a JSONPath query restricted to a specific set of resource IDs (chain step 2).
     *
     * <p>Identical to {@link #build} but adds {@code AND i.id = ANY(?)} and switches
     * {@code ORDER BY} to {@code array_position(?, i.id)} so ES relevance order is preserved.</p>
     *
     * @param idAllowlist non-null, non-empty collection of resource IDs from ES step 1
     */
    /** Network-agnostic overload (no {@code networkId} scoping) — for tests / non-network callers. */
    public QuerySpec buildWithAllowlist(String filters, List<String> rawSchemaContextUrls,
                                        int limit, Collection<String> idAllowlist) {
        return buildWithAllowlist(filters, rawSchemaContextUrls, limit, idAllowlist, null);
    }

    /** Network-scoped allowlist overload without active/validity filtering (delegates with both matches null). */
    public QuerySpec buildWithAllowlist(String filters, List<String> rawSchemaContextUrls,
                                        int limit, Collection<String> idAllowlist, String networkId) {
        return buildWithAllowlist(filters, rawSchemaContextUrls, limit, idAllowlist, networkId, null, null, null);
    }

    public QuerySpec buildWithAllowlist(String filters, List<String> rawSchemaContextUrls,
                                        int limit, Collection<String> idAllowlist, String networkId,
                                        Boolean activeMatch, Boolean validMatch, Instant now) {
        QueryTemplate template = baseTemplate(filterCompiler.compile(filters));
        QuerySpec query = template
                .schemaFiltersPaired(rawSchemaContextUrls)
                .networkFilter(networkId)
                .activeFilter(activeMatch)
                .validityFilter(validMatch, now)
                .idAllowlist(idAllowlist)
                .build(limit);
        log.debug("Built chain JSONPath query with allowlist size={} params={} limit={} activeMatch={} validMatch={}",
                idAllowlist.size(), query.parameters().size(), limit, activeMatch, validMatch);
        return query;
    }

    /**
     * Assembles the base {@link QueryTemplate} (SELECT + JSONPath WHERE condition) for either
     * grammar. Schema/network/active/validity/allowlist conditions are added by the caller —
     * they are grammar-agnostic and identical for both paths.
     */
    private QueryTemplate baseTemplate(CompilationResult result) {
        if (result instanceof CompiledPredicate predicate) {
            QueryTemplate template = QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT)
                    .condition(predicate.whereFragment(), predicate.whereParameters().toArray());
            predicate.offersProjectionFragment().ifPresent(fragment -> template.projectionColumn(
                    QueryBuilderHelper.MATCHING_OFFERS_ALIAS, fragment, predicate.projectionParameters().toArray()));
            return template;
        }

        // LEGACY_POSTGRES — unmodified from the pre-RFC-9535 flow.
        String processedFilter = ((LegacyCompiledFilter) result).postgresJsonpath();
        boolean hasSelectionPath = isSelectionPath(processedFilter);
        String postgresFilter = toPostgresFilter(processedFilter);
        QueryTemplate template = hasSelectionPath
                ? QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT_WITH_FILTER_RESULT, processedFilter)
                : QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT);
        return template.condition(QueryBuilderHelper.JSONPATH_MATCH, postgresFilter);
    }

    /**
     * True when the expression is an absolute path that selects elements (e.g. returns array from jsonb_path_query_array).
     */
    private static boolean isSelectionPath(String processedFilter) {
        if (processedFilter == null || processedFilter.isBlank()) return false;
        String p = processedFilter.trim();
        return p.startsWith("$");
    }

    /** Wraps a processed JSONPath expression into PostgreSQL's exists() syntax (for non-selection filters). */
    private static String toPostgresFilter(String processedFilter) {
        if (processedFilter == null || processedFilter.trim().isEmpty()) {
            log.debug("No filter provided, using default exists path");
            return QueryBuilderHelper.JSONPATH_EXISTS_ALL;
        }
        if (processedFilter.trim().startsWith("$")) {
            log.debug("Using absolute PostgreSQL JSONPath: {}", processedFilter);
            return String.format(QueryBuilderHelper.JSONPATH_EXISTS_PATH, processedFilter);
        }
        String fullPath = String.format(QueryBuilderHelper.JSONPATH_EXISTS_CONDITION, processedFilter);
        log.debug("Generated PostgreSQL JSONPath: {}", fullPath);
        return fullPath;
    }
}
