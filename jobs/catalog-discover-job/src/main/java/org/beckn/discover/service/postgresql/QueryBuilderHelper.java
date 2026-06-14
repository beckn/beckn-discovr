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
     *
     * <p>The join is scoped by <b>both</b> {@code item_id} AND {@code catalog_id}. The
     * {@code item} primary key is {@code (id, catalog_id)} — the same resource id can be
     * published in multiple catalogs at different locations, and
     * {@code item_location_collection} stores one geo row per (item_id, catalog_id).
     * Joining on {@code item_id} alone would let a catalog match another catalog's geo
     * for the same resource id (e.g. a Pune catalog matching a Delhi radius because a
     * Delhi catalog sells the same service). Scoping by {@code catalog_id} keeps each
     * catalog's spatial match to its own locations — mirroring the Elasticsearch path,
     * where each {@code catalogId:resourceId} document carries only its own geo.</p>
     *
     * <p>Format args: pathCondition, stFunction, geomCast, geoFragment, distanceSuffix
     * <ul>
     *   <li>pathCondition: {@code "TRUE"} or {@code "ilc.path = ?"} or {@code "ilc.path IN (?, ?)"}</li>
     *   <li>geomCast: {@code ""} (planar) or {@code "::geography"}</li>
     *   <li>distanceSuffix: {@code ""} or {@code ", ?"}</li>
     * </ul>
     */
    public static final String SPATIAL_EXISTS = "EXISTS (SELECT 1 FROM item_location_collection ilc "
            + "WHERE ilc.item_id = i.id AND ilc.catalog_id = i.catalog_id AND %s AND %s(ilc.geom%s, %s%s))";

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
     * {@code AND i.id = ANY(string_to_array(?, '|'))} clause for the chain
     * allowlist filter. The single {@code ?} binds a {@code String} parameter
     * containing the IDs joined by {@code |}. Using {@code string_to_array}
     * avoids the JDBC-driver overhead of wrapping a {@code String[]} as a
     * {@code java.sql.Array}, which Spring's {@code JdbcClient.params()} does
     * not perform automatically (it would otherwise bind the array via
     * {@code setObject} and PostgreSQL rejects it with
     * {@code op ANY/ALL (array) requires array on right side}).
     */
    public static final String ID_IN_ALLOWLIST = "i.id = ANY(string_to_array(?, '|'))";

    /** Separator used to join allowlist IDs into the single {@code String} bind value. */
    public static final String ID_ALLOWLIST_SEPARATOR = "|";

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
        private String idAllowlist = null;   // null = no allowlist filter; otherwise IDs joined by ID_ALLOWLIST_SEPARATOR

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
            // Sanity-check the IDs do not contain the separator; if any do we
            // would silently split them apart inside Postgres, returning wrong
            // rows. Catalog/resource IDs in the indexed data are alphanumeric
            // with hyphens and underscores only, so '|' is safe.
            for (String id : ids) {
                if (id != null && id.indexOf('|') >= 0) {
                    throw new IllegalArgumentException(
                            "idAllowlist contains an ID with the reserved '|' separator: " + id);
                }
            }
            this.idAllowlist = String.join(ID_ALLOWLIST_SEPARATOR, ids);
            return this;
        }

        /** Builds the final {@link QuerySpec} with WHERE, ORDER BY, and LIMIT. */
        public QuerySpec build(int limit) {
            StringBuilder sql = new StringBuilder(selectFrom);

            // Existing conditions come first so their parameter positions match the
            // order they were added via .condition(). The allowlist condition is
            // appended last and its bind value is appended last — keeping SQL
            // placeholder order in lockstep with the params list.
            List<String> allConditions = new ArrayList<>(conditions);
            List<Object> allParams    = new ArrayList<>(parameters);

            if (idAllowlist != null) {
                allConditions.add(ID_IN_ALLOWLIST);
                allParams.add(idAllowlist);
            }

            if (!allConditions.isEmpty()) {
                sql.append(" WHERE ").append(String.join(" AND ", allConditions));
            }

            if (idAllowlist != null) {
                // Preserve ES relevance order: array_position returns NULL for IDs not in
                // the array, which sorts last — safe because all rows pass the ANY(?) filter.
                sql.append(" ORDER BY array_position(string_to_array(?, '").append(ID_ALLOWLIST_SEPARATOR).append("'), i.id)");
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
