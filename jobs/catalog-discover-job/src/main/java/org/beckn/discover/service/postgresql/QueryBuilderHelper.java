package org.beckn.discover.service.postgresql;

import org.beckn.discover.util.DiscoveryServiceUtil;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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

    /**
     * WHERE condition: network membership. {@code item.network_id} is a {@code text[]};
     * {@code ? = ANY(...)} matches when the resource was published to the requesting
     * network. Binds one {@code String} parameter (the network id). See #309.
     */
    public static final String NETWORK_MATCH = "? = ANY(i.network_id)";

    // ============================
    // SQL constants — active / validity value-match filters (catalog-level)
    // ============================
    //
    // Catalog-level isActive/validity are NOT dedicated columns; they live nested in the item
    // payload at $.catalogs[0]. These predicates are value-match (?active / ?validity) and
    // null-safe per the Beckn spec:
    //   • isActive absent → counts as active (schema default true). active=true matches
    //     value-true OR absent; active=false matches ONLY an explicit false.
    //   • validity absent / open-ended-inside / bare time-of-day / unparseable → counts as valid.
    //     validity=true matches those; validity=false matches ONLY a provably out-of-window date.
    //   • startDate inclusive, endDate inclusive.
    // Values are compared via the exception-safe SQL helper try_to_timestamptz(text) (returns NULL
    // for any value PostgreSQL can't cast — malformed, out-of-range, non-date, or NULL) against a
    // bound "now" parameter — never concatenated, never a raw ::timestamptz cast (which would 500 on
    // a date-shaped-but-invalid value like '2020-13-45'). A NULL parse ⇒ "no usable bound" ⇒ the
    // catalog counts as valid. The helper is provisioned by publish-job migration V6 (prod) and the
    // discover integration test schema.

    /**
     * Catalog-level {@code isActive} value-match: {@code COALESCE(stored, true) = ?}. Binds one
     * {@code Boolean} (the requested value). {@code true} → active-or-absent; {@code false} →
     * explicit false only (absent coalesces to true and is therefore excluded).
     */
    public static final String ACTIVE_MATCH =
            "COALESCE((i.payload #>> '{catalogs,0,isActive}')::boolean, true) = ?";

    /** Validity lower bound (valid direction): {@code startDate} absent/unparseable OR {@code startDate <= now}. Binds one {@code timestamptz}. */
    public static final String VALIDITY_START_MATCH =
            "(try_to_timestamptz(i.payload #>> '{catalogs,0,validity,startDate}') IS NULL "
          + "OR try_to_timestamptz(i.payload #>> '{catalogs,0,validity,startDate}') <= ?)";

    /** Validity upper bound (valid direction): {@code endDate} absent/unparseable OR {@code endDate >= now}. Binds one {@code timestamptz}. */
    public static final String VALIDITY_END_MATCH =
            "(try_to_timestamptz(i.payload #>> '{catalogs,0,validity,endDate}') IS NULL "
          + "OR try_to_timestamptz(i.payload #>> '{catalogs,0,validity,endDate}') >= ?)";

    /**
     * Validity "not currently valid" predicate (invalid direction, {@code validity=false}):
     * a catalog is out-of-window only when it has a <em>parseable</em> {@code startDate} in the
     * future ({@code > now}) OR a parseable {@code endDate} in the past ({@code < now}). Absent,
     * open-ended-inside, bare time-of-day, and unparseable values yield NULL from
     * {@code try_to_timestamptz} → the {@code IS NOT NULL} guard is false → excluded (they count as
     * valid). Binds two {@code timestamptz} params (now, now).
     */
    public static final String VALIDITY_INVALID_MATCH =
            "((try_to_timestamptz(i.payload #>> '{catalogs,0,validity,startDate}') IS NOT NULL "
          + "AND try_to_timestamptz(i.payload #>> '{catalogs,0,validity,startDate}') > ?) "
          + "OR (try_to_timestamptz(i.payload #>> '{catalogs,0,validity,endDate}') IS NOT NULL "
          + "AND try_to_timestamptz(i.payload #>> '{catalogs,0,validity,endDate}') < ?))";

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
     *         .schemaFiltersPaired(rawSchemaContextUrls)
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

        /**
         * Restricts results to catalogs published to {@code networkId} (#309).
         *
         * <p>Adds {@code AND ? = ANY(i.network_id)}. No-op when {@code networkId} is
         * null/blank so callers without a network context (or tests) stay
         * network-agnostic. The value is bound as a {@code ?} parameter — never
         * concatenated.</p>
         */
        public QueryTemplate networkFilter(String networkId) {
            if (networkId == null || networkId.isBlank()) {
                return this;
            }
            return condition(NETWORK_MATCH, networkId);
        }

        /**
         * Adds the catalog-level {@code isActive} value-match condition (the {@code ?active}
         * filter). No-op when {@code activeMatch} is {@code null} (dimension not filtered).
         * Independent of {@link #networkFilter} and {@link #validityFilter} — plain AND-ed
         * WHERE conditions, none gates the other.
         *
         * @param activeMatch {@code null} ⇒ no-op; {@code TRUE} ⇒ active-or-absent;
         *                    {@code FALSE} ⇒ explicitly inactive only
         */
        public QueryTemplate activeFilter(Boolean activeMatch) {
            if (activeMatch == null) {
                return this;
            }
            return condition(ACTIVE_MATCH, activeMatch);
        }

        /**
         * Adds the catalog-level {@code validity} value-match condition (the {@code ?validity}
         * filter), evaluated against {@code $.catalogs[0].validity} of the item payload. No-op
         * when {@code validMatch} is {@code null} (dimension not filtered).
         *
         * <p>{@code TRUE} (currently valid) adds the two inclusive-bound conditions
         * ({@link #VALIDITY_START_MATCH}, {@link #VALIDITY_END_MATCH}); absent/open-ended/
         * bare-time/unparseable values pass. {@code FALSE} (not currently valid) adds
         * {@link #VALIDITY_INVALID_MATCH}; only a provably out-of-window date matches. The
         * {@code now} instant is bound as a {@code timestamptz} {@code ?} — never concatenated.</p>
         *
         * @param validMatch {@code null} ⇒ no-op; {@code TRUE} ⇒ within window; {@code FALSE} ⇒ outside window
         * @param now        the reference instant validity windows are evaluated against
         */
        public QueryTemplate validityFilter(Boolean validMatch, Instant now) {
            if (validMatch == null) {
                return this;
            }
            Objects.requireNonNull(now, "now must not be null when validMatch is set");
            OffsetDateTime ts = now.atOffset(ZoneOffset.UTC);
            if (validMatch) {
                condition(VALIDITY_START_MATCH, ts);
                condition(VALIDITY_END_MATCH, ts);
            } else {
                condition(VALIDITY_INVALID_MATCH, ts, ts);
            }
            return this;
        }

        /**
         * Adds a schema-context filter that preserves {@code (context, type)} pairing.
         *
         * <p>Each {@code schemaContext} entry is a {@code <@context-url>#<@type>}
         * string: the base (before {@code #}) must match {@code i.context_url} and the
         * fragment (after {@code #}) must match {@code i.type} <em>on the same row</em>.
         * Entries are OR'd, so a resource qualifies when it matches at least one full
         * pair:</p>
         * <pre>
         * ( (i.context_url = ? AND i.type = ?)      -- Grocery#GroceryResource
         *   OR (i.context_url = ? AND i.type = ?)   -- Retail#RetailResource
         *   OR (i.context_url = ?) )                -- context-only entry (no #type)
         * </pre>
         *
         * <p>This is the SQL twin of {@code EsSchemaFilterBuilder}'s
         * {@code bool.should[ bool.must[ctx, type] ]} structure. It replaces the old
         * independent {@code context_url IN (...) AND type IN (...)} form, which lost
         * pairing and let cross-pair combinations (e.g. Grocery-ctx + Retail-type)
         * leak through (spec SC-45 / F-14).</p>
         *
         * <p>No-op when {@code rawSchemaContextUrls} is null/empty. All values are
         * bound as {@code ?} parameters — no concatenation of user input.</p>
         *
         * @param rawSchemaContextUrls raw {@code url#type} entries (pairing preserved)
         */
        public QueryTemplate schemaFiltersPaired(List<String> rawSchemaContextUrls) {
            if (rawSchemaContextUrls == null || rawSchemaContextUrls.isEmpty()) {
                return this;
            }
            List<String> pairClauses = new ArrayList<>(rawSchemaContextUrls.size());
            List<Object> pairParams  = new ArrayList<>();
            for (String raw : rawSchemaContextUrls) {
                if (raw == null || raw.isBlank()) continue;
                String base     = DiscoveryServiceUtil.extractBaseUrl(raw);
                String fragment = DiscoveryServiceUtil.extractFragment(raw);
                if (base == null || base.isBlank()) continue;
                if (fragment == null || fragment.isBlank()) {
                    // Context-only entry: match the context, any type.
                    pairClauses.add("(i.context_url = ?)");
                    pairParams.add(base.trim());
                } else {
                    // Paired entry: context AND type must match the same row.
                    pairClauses.add("(i.context_url = ? AND i.type = ?)");
                    pairParams.add(base.trim());
                    pairParams.add(fragment.trim());
                }
            }
            if (pairClauses.isEmpty()) {
                return this;
            }
            String combined = pairClauses.size() == 1
                    ? pairClauses.get(0)
                    : "(" + String.join(" OR ", pairClauses) + ")";
            return condition(combined, pairParams.toArray());
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
