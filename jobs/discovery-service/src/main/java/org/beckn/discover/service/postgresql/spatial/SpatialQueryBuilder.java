package org.beckn.discover.service.postgresql.spatial;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
     * @param schemaTypes       optional {@code item.type} IN-filter
     * @param schemaContextUrls optional {@code item.context_url} IN-filter
     * @param limit             maximum number of rows to return
     * @return a ready-to-execute spec, or {@link Optional#empty()} when no
     *         valid conditions could be built
     */
    public Optional<QuerySpec> build(
            List<DiscoverRequest.SpatialConstraint> constraints,
            List<String> schemaTypes,
            List<String> schemaContextUrls,
            int limit) {

        if (constraints == null || constraints.isEmpty()) {
            log.debug("spatial.build.skip reason=no-constraints");
            return Optional.empty();
        }

        QueryTemplate template = QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT);
        int added = appendSpatialConditions(template, constraints);

        if (added == 0) {
            log.warn("spatial.build.skip reason=no-valid-conditions constraints={}", constraints.size());
            return Optional.empty();
        }

        template.schemaFilters(schemaTypes, schemaContextUrls);
        QuerySpec spec = template.build(limit);
        log.debug("spatial.build.done added={} params={}", added, spec.parameters().size());
        return Optional.of(spec);
    }

    /**
     * Builds a combined JSONPath + spatial {@link QuerySpec} (Path A) as a
     * <b>single SQL query</b>.
     *
     * <p>Both the GIN index (on {@code item.payload} for JSONPath) and the
     * GiST index (on {@code item_location_collection.geom} for spatial) are
     * available to the PostgreSQL planner in one query plan — eliminating two
     * round-trips and the Java-side intersection overhead of the parallel
     * approach.</p>
     *
     * @param constraints       the spatial constraints from the request
     * @param filterExpression  already-validated JSONPath filter expression
     * @param schemaTypes       optional {@code item.type} IN-filter
     * @param schemaContextUrls optional {@code item.context_url} IN-filter
     * @param limit             maximum number of rows to return
     * @return a ready-to-execute spec, or {@link Optional#empty()} when no
     *         valid spatial conditions could be built (caller must fall back)
     */
    public Optional<QuerySpec> buildCombined(
            List<DiscoverRequest.SpatialConstraint> constraints,
            String filterExpression,
            List<String> schemaTypes,
            List<String> schemaContextUrls,
            int limit) {

        if (constraints == null || constraints.isEmpty()) {
            log.debug("spatial.combined.skip reason=no-constraints");
            return Optional.empty();
        }

        // JSONPath condition drives the GIN index; spatial EXISTS drives GiST.
        // The filter must be wrapped in exists() — the @@ operator requires a predicate
        // (boolean-valued path), not a path expression that returns elements.
        QueryTemplate template = QueryBuilderHelper.query(QueryBuilderHelper.BASE_SELECT);
        if (filterExpression != null && !filterExpression.isBlank()) {
            String pgFilter = toPostgresFilter(filterExpression);
            template.condition(QueryBuilderHelper.JSONPATH_MATCH, pgFilter);
        }

        int added = appendSpatialConditions(template, constraints);
        if (added == 0) {
            log.warn("spatial.combined.skip reason=no-valid-spatial-conditions");
            return Optional.empty();
        }

        template.schemaFilters(schemaTypes, schemaContextUrls);
        QuerySpec spec = template.build(limit);
        log.debug("spatial.combined.built added={} params={}", added, spec.parameters().size());
        return Optional.of(spec);
    }

    // ── Filter conversion ─────────────────────────────────────────────────────

    /**
     * Converts a raw user JSONPath filter into a PostgreSQL-compatible predicate
     * suitable for the {@code @@} operator.
     *
     * <p>The {@code @@} operator requires a boolean-valued jsonpath expression.
     * Absolute path filters (starting with {@code $}) are wrapped in
     * {@code exists(...)} so they evaluate as a boolean predicate.
     * Relative conditions (starting with {@code @}) are wrapped in
     * {@code exists($ ? (...))} to apply them to the root document.</p>
     *
     * <p>Colon-field names (e.g. {@code beckn:id}) are quoted via
     * {@link JsonPathConverter} so PostgreSQL parses them correctly.</p>
     */
    private String toPostgresFilter(String filterExpression) {
        String processed = jsonPathConverter.processFilter(filterExpression);
        if (processed.isBlank()) return QueryBuilderHelper.JSONPATH_EXISTS_ALL;
        String trimmed = processed.trim();
        if (trimmed.startsWith("$")) {
            return String.format(QueryBuilderHelper.JSONPATH_EXISTS_PATH, trimmed);
        }
        return String.format(QueryBuilderHelper.JSONPATH_EXISTS_CONDITION, trimmed);
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
        for (DiscoverRequest.SpatialConstraint c : constraints) {
            if (addCondition(template, c)) added++;
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
            log.warn("spatial.condition.skip reason=null-constraint");
            return false;
        }

        String op = c.getOperation();
        if (!StringUtils.hasText(op)) {
            log.warn("spatial.condition.skip reason=blank-operation");
            return false;
        }

        SpatialOp spatialOp = OPERATIONS.get(op);
        if (spatialOp == null) {
            log.warn("spatial.condition.skip reason=unsupported-operation op={} supported={}", op, OPERATIONS.keySet());
            return false;
        }

        if (c.getGeometry() == null) {
            log.warn("spatial.condition.skip reason=null-geometry op={}", op);
            return false;
        }

        if (spatialOp.hasDistance() && c.getDistanceMeters() == null) {
            log.warn("spatial.condition.skip reason=missing-distance op={}", op);
            return false;
        }

        String geoJson;
        try {
            geoJson = objectMapper.writeValueAsString(c.getGeometry());
        } catch (JsonProcessingException e) {
            log.error("spatial.condition.skip reason=geometry-serialise-failed op={} error={}", op, e.getMessage());
            return false;
        }

        // Template and geo-fragment selection — all function names from compile-time constants
        String existsTemplate;
        String geoFragment;
        List<Object> params = new ArrayList<>(2);
        params.add(geoJson); // ? for ST_GeomFromGeoJSON(?)

        if (spatialOp.useGeography()) {
            geoFragment    = QueryBuilderHelper.GEOJSON_GEOGRAPHY;
            existsTemplate = spatialOp.hasDistance()
                    ? QueryBuilderHelper.SPATIAL_EXISTS_CONDITION_DIST_GEOGRAPHY
                    : QueryBuilderHelper.SPATIAL_EXISTS_CONDITION_GEOGRAPHY;
        } else {
            geoFragment    = QueryBuilderHelper.GEOJSON_GEOMETRY;
            existsTemplate = spatialOp.hasDistance()
                    ? QueryBuilderHelper.SPATIAL_EXISTS_CONDITION_DIST
                    : QueryBuilderHelper.SPATIAL_EXISTS_CONDITION;
        }

        if (spatialOp.hasDistance()) {
            params.add(c.getDistanceMeters()); // ? for distance value
        }

        // String.format receives only compile-time constants (stFunction, geoFragment)
        String condition = String.format(existsTemplate, spatialOp.stFunction(), geoFragment);
        template.condition(condition, params.toArray());

        log.debug("spatial.condition.added op={} fn={} geography={} distanceMeters={}",
                op, spatialOp.stFunction(), spatialOp.useGeography(),
                spatialOp.hasDistance() ? c.getDistanceMeters() : "n/a");
        return true;
    }
}
