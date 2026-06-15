package org.beckn.discover.service.postgresql.spatial;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.logging.LogEvent;
import org.beckn.discover.model.DiscoverRequest;
import org.beckn.discover.service.postgresql.QueryBuilderHelper;
import org.beckn.discover.service.postgresql.QueryBuilderHelper.QuerySpec;
import org.beckn.discover.service.postgresql.QueryBuilderHelper.QueryTemplate;
import org.beckn.discover.service.postgresql.jsonpath.JsonPathConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds PostGIS spatial queries against the pre-indexed
 * {@code item_location_collection} table.
 *
 * <h3>Why EXISTS (not JOIN)?</h3>
 * <p>A JOIN produces one output row per matching geometry. An item with 3 stored
 * GPS points would appear 3 times, requiring GROUP BY or DISTINCT to collapse
 * them — which forces PostgreSQL to sort/hash the {@code payload} JSONB column.
 * EXISTS short-circuits at the first matching row, so each item appears exactly
 * once and {@code ORDER BY i.updated_at} works without any aggregation.</p>
 *
 * <h3>SQL injection prevention</h3>
 * <p>ST_* function names are sourced exclusively from the {@link #OPERATIONS}
 * allow-list (compile-time constants).  All user-supplied values — GeoJSON
 * text, distance metres, filter expressions — flow through JDBC {@code ?}
 * bind parameters.  No user data is ever interpolated into SQL text.</p>
 *
 * <h3>Adding a new spatial operation</h3>
 * Add a single entry to {@link #OPERATIONS}; no other code changes required.
 */
@Component
public class SpatialQueryBuilder {

    private static final Logger log = LoggerFactory.getLogger(SpatialQueryBuilder.class);

    /**
     * Descriptor for a PostGIS spatial operation.
     *
     * @param stFunction   PostGIS function name (e.g. {@code ST_DWithin})
     * @param useGeography {@code true} → cast {@code ilc.geom} to
     *                     {@code ::geography} for metre-accurate computation;
     *                     {@code false} → planar geometry
     * @param hasDistance  {@code true} when the function requires a distance
     *                     as its third argument
     */
    private record SpatialOp(String stFunction, boolean useGeography, boolean hasDistance) {}

    /**
     * Allowed spatial operations keyed by the {@code op} field in the
     * discover request.  Only names from this map ever appear in generated
     * SQL — unsupported operations are rejected at build time.
     *
     * <p>To add a new operation: add one entry here.  AR-2.1 compliance.</p>
     */
    private static final Map<String, SpatialOp> OPERATIONS = Map.of(
            "s_dwithin",    new SpatialOp("ST_DWithin",    true,  true),
            "s_intersects", new SpatialOp("ST_Intersects", true,  false),
            "s_contains",   new SpatialOp("ST_Contains",   false, false),
            "s_within",     new SpatialOp("ST_Within",     false, false),
            "s_disjoint",   new SpatialOp("ST_Disjoint",   false, false),
            "s_overlaps",   new SpatialOp("ST_Overlaps",   false, false),
            "s_crosses",    new SpatialOp("ST_Crosses",    false, false),
            "s_touches",    new SpatialOp("ST_Touches",    false, false),
            "s_equals",     new SpatialOp("ST_Equals",     false, false)
    );

    private final ObjectMapper objectMapper;
    private final JsonPathConverter jsonPathConverter;

    public SpatialQueryBuilder(ObjectMapper objectMapper, JsonPathConverter jsonPathConverter) {
        this.objectMapper = objectMapper;
        this.jsonPathConverter = jsonPathConverter;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Builds a spatial-only {@link QuerySpec} (Path C).
     *
     * <p>Each spatial constraint becomes one correlated EXISTS subquery in the
     * WHERE clause.  Multiple constraints are ANDed — all must match.</p>
     *
     * @param constraints       the spatial constraints to apply; must not be null
     * @param rawSchemaContextUrls optional schemaContext url#type entries (paired filter)
     * @param limit             maximum number of rows to return
     * @return a ready-to-execute spec, or {@link Optional#empty()} when no
     *         valid conditions could be built
     */
    public Optional<QuerySpec> build(
            List<DiscoverRequest.SpatialConstraint> constraints,
            List<String> rawSchemaContextUrls,
            int limit) {

        if (constraints == null || constraints.isEmpty()) {
            log.debug("event={} reason=no-constraints", LogEvent.SPATIAL_BUILD_SKIP);
            return Optional.empty();
        }

        QueryTemplate template = QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT);
        int added = appendSpatialConditions(template, constraints);

        if (added == 0) {
            log.warn("event={} reason=no-valid-conditions constraints={}", LogEvent.SPATIAL_BUILD_SKIP, constraints.size());
            return Optional.empty();
        }

        template.schemaFiltersPaired(rawSchemaContextUrls);
        QuerySpec spec = template.build(limit);
        log.debug("event={} added={} params={}", LogEvent.SPATIAL_BUILD_DONE, added, spec.parameters().size());
        return Optional.of(spec);
    }

    /**
     * Builds a combined JSONPath + spatial {@link QuerySpec} (Path A) as a
     * <b>single SQL query</b>.
     *
     * <p>Both the GIN index (on {@code item.payload} for JSONPath) and the
     * GiST index (on {@code item_location_collection.geom} for spatial) are
     * available to the PostgreSQL planner in one query plan, eliminating
     * Java-side intersection of two separately-bounded result sets.</p>
     *
     * @param constraints       the spatial constraints from the request
     * @param filterExpression  already-validated JSONPath filter expression
     * @param rawSchemaContextUrls optional schemaContext url#type entries (paired filter)
     * @param limit             maximum number of rows to return
     * @return a ready-to-execute spec, or {@link Optional#empty()} when no
     *         valid spatial conditions could be built (caller must fall back)
     */
    public Optional<QuerySpec> buildCombined(
            List<DiscoverRequest.SpatialConstraint> constraints,
            String filterExpression,
            List<String> rawSchemaContextUrls,
            int limit) {

        if (constraints == null || constraints.isEmpty()) {
            log.debug("event={} reason=no-constraints", LogEvent.SPATIAL_COMBINED_SKIP);
            return Optional.empty();
        }

        // JSONPath condition drives the GIN index; spatial EXISTS drives GiST.
        // combinedBaseTemplate picks the SELECT (matching_offers projection for selection
        // paths) and applies the exists()-wrapped @@ predicate from a single processed filter.
        QueryTemplate template = combinedBaseTemplate(filterExpression);

        int added = appendSpatialConditions(template, constraints);
        if (added == 0) {
            log.warn("event={} reason=no-valid-spatial-conditions", LogEvent.SPATIAL_COMBINED_SKIP);
            return Optional.empty();
        }

        template.schemaFiltersPaired(rawSchemaContextUrls);
        QuerySpec spec = template.build(limit);
        log.debug("event={} added={} params={}", LogEvent.SPATIAL_COMBINED_BUILT, added, spec.parameters().size());
        return Optional.of(spec);
    }

    /**
     * Builds a combined JSONPath + spatial + ID-allowlist query (chain step 2, case 7).
     *
     * <p>Identical to {@link #buildCombined} but adds {@code AND i.id = ANY(?)} and
     * switches {@code ORDER BY} to {@code array_position(?, i.id)} so ES relevance
     * order is preserved while geo conditions are redundantly enforced belt-and-
     * suspenders style.</p>
     *
     * @param idAllowlist non-null, non-empty collection of resource IDs from ES step 1
     * @return {@link Optional#empty()} when no valid spatial conditions could be built
     */
    public Optional<QuerySpec> buildCombinedWithAllowlist(
            List<DiscoverRequest.SpatialConstraint> constraints,
            String filterExpression,
            List<String> rawSchemaContextUrls,
            int limit,
            Collection<String> idAllowlist) {

        if (constraints == null || constraints.isEmpty()) {
            log.debug("event={} reason=no-constraints", LogEvent.SPATIAL_COMBINED_SKIP);
            return Optional.empty();
        }

        QueryTemplate template = combinedBaseTemplate(filterExpression);

        int added = appendSpatialConditions(template, constraints);
        if (added == 0) {
            log.warn("event={} reason=no-valid-spatial-conditions", LogEvent.SPATIAL_COMBINED_SKIP);
            return Optional.empty();
        }

        template.schemaFiltersPaired(rawSchemaContextUrls)
                .idAllowlist(idAllowlist);
        QuerySpec spec = template.build(limit);
        log.debug("event={} added={} allowlistSize={} params={}",
                LogEvent.SPATIAL_COMBINED_BUILT + ".chain", added, idAllowlist.size(), spec.parameters().size());
        return Optional.of(spec);
    }

    // ── Filter conversion ─────────────────────────────────────────────────────

    /**
     * Builds the base template for a combined (JSONPath + spatial) query AND applies the
     * JSONPath {@code @@} predicate. The raw filter is processed <b>once</b> here
     * ({@link JsonPathConverter#processFilter} is deterministic) and reused for both the
     * SELECT projection and the WHERE predicate, so the two can never desync.
     *
     * <p>When the filter is a selection path (starts with {@code $}) the template projects
     * the {@code matching_offers} column ({@link QueryBuilderHelper#BASE_SELECT_WITH_FILTER_RESULT},
     * processed filter bound first so parameter order stays in lockstep with the SQL
     * placeholders) — mirroring
     * {@link org.beckn.discover.service.postgresql.jsonpath.JsonPathQueryBuilder} so an
     * offer-selection filter (e.g. {@code $.catalogs[*].offers[*] ? (...)}) narrows the
     * returned offers identically whether or not a spatial constraint is present
     * (Finding 2). Non-selection filters use {@link QueryBuilderHelper#BASE_SELECT}; a
     * blank filter yields a bare {@code BASE_SELECT} with no JSONPath predicate.</p>
     */
    private QueryTemplate combinedBaseTemplate(String filterExpression) {
        String processed = (filterExpression != null && !filterExpression.isBlank())
                ? jsonPathConverter.processFilter(filterExpression) : "";
        String trimmed = processed.trim();
        boolean selectionPath = trimmed.startsWith("$");
        QueryTemplate template = selectionPath
                ? QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT_WITH_FILTER_RESULT, processed)
                : QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT);
        if (!trimmed.isEmpty()) {
            template.condition(QueryBuilderHelper.JSONPATH_MATCH, existsPredicate(trimmed));
        }
        return template;
    }

    /**
     * Wraps an already-processed jsonpath into the boolean {@code exists(...)} predicate
     * required by the {@code @@} operator. Absolute paths ({@code $...}) → {@code exists($...)};
     * relative conditions ({@code @...}) → {@code exists($ ? (...))}.
     */
    private static String existsPredicate(String trimmedProcessed) {
        if (trimmedProcessed.isEmpty()) return QueryBuilderHelper.JSONPATH_EXISTS_ALL;
        if (trimmedProcessed.startsWith("$")) {
            return String.format(QueryBuilderHelper.JSONPATH_EXISTS_PATH, trimmedProcessed);
        }
        return String.format(QueryBuilderHelper.JSONPATH_EXISTS_CONDITION, trimmedProcessed);
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Appends one EXISTS subquery condition for each valid constraint.
     *
     * @return number of conditions successfully added
     */
    private int appendSpatialConditions(QueryTemplate template,
            List<DiscoverRequest.SpatialConstraint> constraints) {
        int added = 0;
        for (DiscoverRequest.SpatialConstraint constraint : constraints) {
            if (addCondition(template, constraint)) added++;
        }
        return added;
    }

    /**
     * Translates one {@link DiscoverRequest.SpatialConstraint} into a
     * parameterised EXISTS subquery and appends it to {@code template}.
     *
     * <p>Returns {@code false} (not an exception) when the constraint is
     * invalid so that remaining constraints in the same request can still
     * be processed — NFR-4.3 compliance.</p>
     */
    private boolean addCondition(QueryTemplate template, DiscoverRequest.SpatialConstraint c) {
        if (c == null) {
            log.warn("event={} reason=null-constraint", LogEvent.SPATIAL_CONDITION_SKIP);
            return false;
        }

        String op = c.getOperation();
        if (!StringUtils.hasText(op)) {
            log.warn("event={} reason=blank-operation", LogEvent.SPATIAL_CONDITION_SKIP);
            return false;
        }

        SpatialOp spatialOp = OPERATIONS.get(op);
        if (spatialOp == null) {
            log.warn("event={} reason=unsupported-operation op={} supported={}", LogEvent.SPATIAL_CONDITION_SKIP, op, OPERATIONS.keySet());
            return false;
        }

        if (c.getGeometry() == null) {
            log.warn("event={} reason=null-geometry op={}", LogEvent.SPATIAL_CONDITION_SKIP, op);
            return false;
        }

        if (spatialOp.hasDistance() && c.getDistanceMeters() == null) {
            log.warn("event={} reason=missing-distance op={}", LogEvent.SPATIAL_CONDITION_SKIP, op);
            return false;
        }

        String geoJson;
        try {
            geoJson = objectMapper.writeValueAsString(c.getGeometry());
        } catch (JsonProcessingException e) {
            log.error("event={} reason=geometry-serialise-failed op={} error={}", LogEvent.SPATIAL_CONDITION_SKIP, op, e.getMessage());
            return false;
        }

        // Resolve targets — filter by ilc.path when specified (pass-through, no conversion)
        List<String> paths = toPathList(c.getTargets());
        boolean usePathFilter = !paths.isEmpty();

        String pathCondition;
        List<Object> pathParams = new ArrayList<>();
        if (usePathFilter) {
            pathCondition = paths.size() == 1
                    ? "ilc.path = ?"
                    : "ilc.path IN (" + "?, ".repeat(paths.size() - 1) + "?)";
            pathParams.addAll(paths);
        } else {
            pathCondition = QueryBuilderHelper.SPATIAL_PATH_ANY;
        }

        String geoFragment = spatialOp.useGeography()
                ? QueryBuilderHelper.GEOJSON_GEOGRAPHY
                : QueryBuilderHelper.GEOJSON_GEOMETRY;
        String geomCast = spatialOp.useGeography() ? "::geography" : "";
        String distanceSuffix = spatialOp.hasDistance() ? ", ?" : "";

        List<Object> params = new ArrayList<>(pathParams.size() + 2);
        params.addAll(pathParams);
        params.add(geoJson);
        if (spatialOp.hasDistance()) {
            params.add(c.getDistanceMeters());
        }

        String condition = String.format(QueryBuilderHelper.SPATIAL_EXISTS,
                pathCondition, spatialOp.stFunction(), geomCast, geoFragment, distanceSuffix);
        template.condition(condition, params.toArray());

        log.debug("event={} op={} fn={} geography={} distanceMeters={} pathFilter={}",
                LogEvent.SPATIAL_CONDITION_ADDED, op, spatialOp.stFunction(), spatialOp.useGeography(),
                spatialOp.hasDistance() ? c.getDistanceMeters() : null, usePathFilter);
        return true;
    }

    /** Extracts path strings from targets (string or list). Pass-through, no conversion. */
    private List<String> toPathList(Object targets) {
        List<String> out = new ArrayList<>();
        if (targets == null) return out;
        if (targets instanceof String s) {
            if (StringUtils.hasText(s)) out.add(s.trim());
        } else if (targets instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && StringUtils.hasText(s)) out.add(s.trim());
            }
        }
        return out;
    }
}
