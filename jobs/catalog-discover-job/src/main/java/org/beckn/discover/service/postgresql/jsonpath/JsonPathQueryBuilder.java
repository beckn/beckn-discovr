package org.beckn.discover.service.postgresql.jsonpath;

import org.beckn.discover.service.postgresql.QueryBuilderHelper;
import org.beckn.discover.service.postgresql.QueryBuilderHelper.QuerySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds JSONPath-based PostgreSQL queries.
 *
 * <p>When the user supplies a selection path (starts with $), we add a filter-result column
 * (jsonb_path_query_array) so the response can show only matched offers when the path returns
 * offer-like objects. WHERE uses exists(path). No offer-scoped heuristic — any path format works.</p>
 */
@Component
public class JsonPathQueryBuilder {

    private static final Logger log = LoggerFactory.getLogger(JsonPathQueryBuilder.class);

    private final JsonPathConverter jsonPathConverter;

    public JsonPathQueryBuilder(JsonPathConverter jsonPathConverter) {
        this.jsonPathConverter = jsonPathConverter;
    }

    /**
     * Builds a complete JSONPath query (SQL + params) with optional schema filters and limit.
     * When filter is a selection path (starts with $), always adds filter-result column so
     * response can show only matched offers/items regardless of expression format.
     */
    public QuerySpec build(String filters, List<String> schemaTypes, List<String> schemaContextUrls, int limit) {
        String processedFilter = jsonPathConverter.processFilter(filters);
        boolean hasSelectionPath = isSelectionPath(processedFilter);
        String postgresFilter = toPostgresFilter(processedFilter);

        var template = hasSelectionPath
                ? QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT_WITH_FILTER_RESULT, processedFilter)
                : QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT);
        QuerySpec query = template
                .condition(QueryBuilderHelper.JSONPATH_MATCH, postgresFilter)
                .schemaFilters(schemaTypes, schemaContextUrls)
                .build(limit);
        log.debug("Built JSONPath query with {} parameters, limit {}", query.parameters().size(), limit);
        return query;
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
