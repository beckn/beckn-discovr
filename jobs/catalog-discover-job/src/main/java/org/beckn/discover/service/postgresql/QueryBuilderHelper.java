package org.beckn.discover.service.postgresql;

import org.beckn.discover.util.DiscoveryServiceUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Central SQL dictionary and query builder for the {@code postgresql} package.
 *
 * <p>
 * All SQL templates are defined here as constants. The builders
 * ({@link org.beckn.discover.service.postgresql.jsonpath.JsonPathQueryBuilder},
 * {@link org.beckn.discover.service.postgresql.spatial.SpatialQueryBuilder})
 * reference these constants and use {@link QueryTemplate} to assemble the final
 * query.
 * </p>
 */
public final class QueryBuilderHelper {

    private static final int DEFAULT_LIMIT = 100;

    // ============================
    // SQL constants — Common
    // ============================

    /** Base resource SELECT — used by JSONPath and spatial queries. */
    public static final String BASE_SELECT = "SELECT i.id, i.catalog_id, i.payload AS resource_payload FROM item i";

    /**
     * Resource SELECT with filter-result column. Use when user supplies a selection
     * path (starts with $).
     * WHERE uses exists(path); SELECT projects matched elements via
     * jsonb_path_query_array.
     */
    public static final String BASE_SELECT_WITH_FILTER_RESULT = "SELECT i.id, i.catalog_id, "
            + "jsonb_path_query_array(i.payload, CAST(? AS jsonpath)) AS matching_offers, "
            + "i.payload AS resource_payload FROM item i";

    /**
     * Column alias for the filter-result projection. Used when reading result rows.
     */
    public static final String MATCHING_OFFERS_ALIAS = "matching_offers";

    // ============================
    // SQL constants — JSONPath
    // ============================

    /** WHERE condition: JSONPath filter match against the item payload. */
    public static final String JSONPATH_MATCH = "i.payload @@ CAST(? AS jsonpath)";

    /** JSONPath exists — match everything (no filter provided). */
    public static final String JSONPATH_EXISTS_ALL = "exists($)";

    /**
     * JSONPath exists — absolute path check. Use with
     * {@code String.format(JSONPATH_EXISTS_PATH, path)}.
     */
    public static final String JSONPATH_EXISTS_PATH = "exists(%s)";

    /**
     * JSONPath exists — conditional match. Use with
     * {@code String.format(JSONPATH_EXISTS_CONDITION, condition)}.
     */
    public static final String JSONPATH_EXISTS_CONDITION = "exists($ ? (%s))";

    // ============================
    // SQL constants — Spatial (PostGIS via item_location_collection)
    // ============================

    /**
     * Correlated EXISTS subquery for spatial filtering.
     * <p>Format args: pathCondition, stFunction, geomCast, geoFragment, distanceSuffix
     * <ul>
     *   <li>pathCondition: {@code "TRUE"} or {@code "ilc.path = ?"} or {@code "ilc.path IN (?, ?)"}</li>
     *   <li>geomCast: {@code ""} (planar) or {@code "::geography"}</li>
     *   <li>distanceSuffix: {@code ""} or {@code ", ?"}</li>
     * </ul>
     */
    public static final String SPATIAL_EXISTS = "EXISTS (SELECT 1 FROM item_location_collection ilc "
            + "WHERE ilc.item_id = i.id AND %s AND %s(ilc.geom%s, %s%s))";

    /** Path condition when no targets: match any geometry. */
    public static final String SPATIAL_PATH_ANY = "TRUE";

    /**
     * User-supplied GeoJSON geometry cast as geographic type (SRID 4326, units =
     * metres).
     * Bind one {@code String} parameter: the GeoJSON text.
     * Use for distance-based operations: ST_DWithin, etc.
     */
    public static final String GEOJSON_GEOGRAPHY = "ST_GeomFromGeoJSON(?::text)::geography";

    /**
     * User-supplied GeoJSON geometry cast as planar geometry with SRID 4326.
     * Bind one {@code String} parameter: the GeoJSON text.
     * Use for topological operations: ST_Contains, ST_Within, ST_Intersects, etc.
     */
    public static final String GEOJSON_GEOMETRY = "ST_SetSRID(ST_GeomFromGeoJSON(?::text), 4326)";

    // ============================
    // QuerySpec (immutable result)
    // ============================

    /** Immutable SQL + bound parameters. Pass to JdbcTemplate for execution. */
    public record QuerySpec(String sql, List<Object> parameters) {
        public QuerySpec {
            parameters = parameters != null ? List.copyOf(parameters) : List.of();
        }
    }

    // ============================
    // QueryTemplate (fluent builder)
    // ============================

    /**
     * Creates a new {@link QueryTemplate} with the given SELECT...FROM clause.
     *
     * @param selectFrom   base SQL constant (e.g. {@link #BASE_SELECT})
     * @param selectParams bound parameters for any {@code ?} in the SELECT clause
     */
    public static QueryTemplate query(String selectFrom, Object... selectParams) {
        return new QueryTemplate(selectFrom, selectParams);
    }

    /**
     * {@code AND i.id = ANY(?)} clause for the chain allowlist filter.
     * The single {@code ?} binds a {@code String[]} array parameter.
     */
    public static final String ID_IN_ALLOWLIST = "i.id = ANY(?)";

    /**
     * Fluent builder that assembles a query from a base SELECT, WHERE conditions,
     * and LIMIT.
     *
     * <pre>
     * QueryBuilderHelper.query(BASE_SELECT)
     *         .condition(JSONPATH_MATCH, filterValue)
     *         .schemaFilters(schemaTypes, schemaContextUrls)
     *         .build(limit);
     * </pre>
     */
    public static final class QueryTemplate {
        private final String selectFrom;
        private final List<String> conditions = new ArrayList<>();
        private final List<Object> parameters = new ArrayList<>();
        private String[] idAllowlist = null;   // null = no allowlist filter

        QueryTemplate(String selectFrom, Object... selectParams) {
            this.selectFrom = selectFrom;
            Collections.addAll(this.parameters, selectParams);
        }

        /** Adds a WHERE/AND condition with bound parameters. */
        public QueryTemplate condition(String clause, Object... params) {
            conditions.add(clause);
            Collections.addAll(parameters, params);
            return this;
        }

        /** Adds schema type and context_url IN-clause filters when present. */
        public QueryTemplate schemaFilters(List<String> schemaTypes, List<String> schemaContextUrls) {
            DiscoveryServiceUtil.buildInClause("i.type", schemaTypes, parameters)
                    .ifPresent(conditions::add);
            DiscoveryServiceUtil.buildInClause("i.context_url", schemaContextUrls, parameters)
                    .ifPresent(conditions::add);
            return this;
        }

        /**
         * Restricts the query to resources whose {@code id} is in {@code ids}.
         *
         * <p>When present:
         * <ul>
         *   <li>Adds {@code AND i.id = ANY(?)} to the WHERE clause.</li>
         *   <li>Switches ORDER BY to {@code array_position(?, i.id)} so PSQL
         *       returns rows in the same rank order as the ES candidate list.</li>
         * </ul>
         * Callers must pass a non-null, non-empty collection.
         */
        public QueryTemplate idAllowlist(Collection<String> ids) {
            this.idAllowlist = ids.toArray(String[]::new);
            return this;
        }

        /** Builds the final {@link QuerySpec} with WHERE, ORDER BY, and LIMIT. */
        public QuerySpec build(int limit) {
            StringBuilder sql = new StringBuilder(selectFrom);

            // Append allowlist condition before other conditions so the planner can
            // use the primary-key index for the ANY(?) lookup early.
            List<String> allConditions = new ArrayList<>();
            List<Object> allParams    = new ArrayList<>(parameters);

            if (idAllowlist != null) {
                allConditions.add(ID_IN_ALLOWLIST);
                allParams.add(idAllowlist);
            }
            allConditions.addAll(conditions);

            if (!allConditions.isEmpty()) {
                sql.append(" WHERE ").append(String.join(" AND ", allConditions));
            }

            if (idAllowlist != null) {
                // Preserve ES relevance order: array_position returns NULL for IDs not in
                // the array, which sorts last — safe because all rows pass the ANY(?) filter.
                sql.append(" ORDER BY array_position(?, i.id)");
                allParams.add(idAllowlist);
            } else {
                sql.append(" ORDER BY i.updated_at DESC");
            }
            sql.append(" LIMIT ").append(sanitizeLimit(limit));
            return new QuerySpec(sql.toString(), allParams);
        }
    }

    // ============================
    // Utilities
    // ============================

    /** Returns a safe limit (default if <= 0). */
    static int sanitizeLimit(int limit) {
        return limit > 0 ? limit : DEFAULT_LIMIT;
    }

    private QueryBuilderHelper() {
        // utility
    }
}
