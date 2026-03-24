package org.beckn.discover.service.elasticsearch;

import co.elastic.clients.elasticsearch._types.GeoShapeRelation;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.model.DiscoverRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds Elasticsearch geo_shape queries from Beckn SpatialConstraint objects.
 *
 * Each constraint targets a specific location field in the ES document via the
 * path normalization: $.catalogs[*].beckn:items[*].beckn:availableAt[*].geo → loc_beckn_availableAt.geo
 */
@Component
@ConditionalOnProperty(name = "discovery.spatial.engine", havingValue = "elasticsearch")
public class EsSpatialQueryBuilder {

    private static final Logger log = LoggerFactory.getLogger(EsSpatialQueryBuilder.class);

    private static final Map<String, GeoShapeRelation> RELATIONS = Map.of(
            "s_intersects", GeoShapeRelation.Intersects,
            "s_within",     GeoShapeRelation.Within,
            "s_contains",   GeoShapeRelation.Contains,
            "s_disjoint",   GeoShapeRelation.Disjoint
    );
    private static final Set<String> UNSUPPORTED =
            Set.of("s_overlaps", "s_crosses", "s_touches", "s_equals");

    private final ObjectMapper mapper;

    public EsSpatialQueryBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Converts a list of SpatialConstraints to ES geo_shape queries ANDed together.
     *
     * @return Optional.empty() if no valid queries could be built (all skipped);
     *         otherwise the list of queries to AND in a bool.must clause
     */
    public Optional<List<Query>> buildGeoShapeQueries(
            List<DiscoverRequest.SpatialConstraint> constraints) {

        List<Query> queries = new ArrayList<>();

        for (DiscoverRequest.SpatialConstraint c : constraints) {
            String op = c.getOperation();

            if (UNSUPPORTED.contains(op)) {
                log.warn("es.spatial.unsupported-op op={} — skipping", op);
                continue;
            }

            String quantifier = c.getQuantifier();
            if ("all".equals(quantifier) || "none".equals(quantifier)) {
                log.warn("es.spatial.unsupported-quantifier quantifier={} — skipping", quantifier);
                continue;
            }

            String fieldName = resolveFieldName(c.getTargets());
            if (fieldName == null) {
                log.warn("es.spatial.no-valid-target targets={} — skipping", c.getTargets());
                continue;
            }
            String queryField = fieldName + ".geo";

            Query query = buildQuery(op, queryField, c);
            if (query != null) queries.add(query);
        }

        return queries.isEmpty() ? Optional.empty() : Optional.of(queries);
    }

    /**
     * Builds the exact ES request body JSON for a spatial search — for logging only.
     * The returned string can be copied directly into a curl or Kibana request.
     */
    @SuppressWarnings("unchecked")
    public String buildRequestJson(List<DiscoverRequest.SpatialConstraint> constraints,
                                   String alias, int limit) {
        List<Map<String, Object>> mustClauses = new ArrayList<>();
        for (DiscoverRequest.SpatialConstraint c : constraints) {
            String op = c.getOperation();
            if (UNSUPPORTED.contains(op)) continue;
            String quantifier = c.getQuantifier();
            if ("all".equals(quantifier) || "none".equals(quantifier)) continue;
            String fieldName = resolveFieldName(c.getTargets());
            if (fieldName == null) continue;
            String queryField = fieldName + ".geo";

            try {
                Map<String, Object> shape;
                String relation;
                if ("s_dwithin".equals(op)) {
                    List<?> coords = (List<?>) c.getGeometry().getCoordinates();
                    double lon = ((Number) coords.get(0)).doubleValue();
                    double lat = ((Number) coords.get(1)).doubleValue();
                    shape = new LinkedHashMap<>();
                    shape.put("type", "circle");
                    shape.put("coordinates", List.of(lon, lat));
                    shape.put("radius", c.getDistanceMeters().longValue() + "m");
                    relation = "INTERSECTS";
                } else {
                    shape = mapper.convertValue(c.getGeometry(), Map.class);
                    relation = RELATIONS.get(op).jsonValue().toUpperCase();
                }
                Map<String, Object> fieldClause = new LinkedHashMap<>();
                fieldClause.put("shape", shape);
                fieldClause.put("relation", relation);
                mustClauses.add(Map.of("geo_shape", Map.of(queryField, fieldClause)));
            } catch (Exception e) {
                // skip bad constraint in JSON log — actual query already skipped above
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", limit);
        body.put("query", Map.of("bool", Map.of("must", mustClauses)));
        try {
            return "POST /" + alias + "/_search\n"
                    + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
        } catch (Exception e) {
            return "(json-serialization failed: " + e.getMessage() + ")";
        }
    }

    /**
     * Builds the exact ES request body JSON for a combined spatial + text search — for logging only.
     */
    public String buildCombinedRequestJson(List<DiscoverRequest.SpatialConstraint> constraints,
                                           String textSearch, String alias, int limit, double minScore) {
        // Reuse spatial clause building from buildRequestJson
        String spatialPart = buildRequestJson(constraints, alias, limit);
        // Extract must clauses from spatial JSON and append multi_match
        Map<String, Object> multiMatch = new LinkedHashMap<>();
        multiMatch.put("query", textSearch);
        multiMatch.put("fields", List.of("full_text_blob", "item_name^2"));
        multiMatch.put("type", "best_fields");
        multiMatch.put("fuzziness", "AUTO");

        // Rebuild full body with both spatial + text must clauses
        List<Map<String, Object>> mustClauses = new ArrayList<>();
        // Re-build spatial clauses (same logic as buildRequestJson)
        for (DiscoverRequest.SpatialConstraint c : constraints) {
            String op = c.getOperation();
            if (UNSUPPORTED.contains(op)) continue;
            String quantifier = c.getQuantifier();
            if ("all".equals(quantifier) || "none".equals(quantifier)) continue;
            String fieldName = resolveFieldName(c.getTargets());
            if (fieldName == null) continue;
            String queryField = fieldName + ".geo";
            try {
                Map<String, Object> shape;
                String relation;
                if ("s_dwithin".equals(op)) {
                    List<?> coords = (List<?>) c.getGeometry().getCoordinates();
                    double lon = ((Number) coords.get(0)).doubleValue();
                    double lat = ((Number) coords.get(1)).doubleValue();
                    shape = new LinkedHashMap<>();
                    shape.put("type", "circle");
                    shape.put("coordinates", List.of(lon, lat));
                    shape.put("radius", c.getDistanceMeters().longValue() + "m");
                    relation = "INTERSECTS";
                } else {
                    shape = mapper.convertValue(c.getGeometry(), Map.class);
                    relation = RELATIONS.get(op).jsonValue().toUpperCase();
                }
                Map<String, Object> fieldClause = new LinkedHashMap<>();
                fieldClause.put("shape", shape);
                fieldClause.put("relation", relation);
                mustClauses.add(Map.of("geo_shape", Map.of(queryField, fieldClause)));
            } catch (Exception ignored) {}
        }
        mustClauses.add(Map.of("multi_match", multiMatch));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("min_score", minScore);
        body.put("size", limit);
        body.put("query", Map.of("bool", Map.of("must", mustClauses)));
        try {
            return "POST /" + alias + "/_search\n"
                    + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
        } catch (Exception e) {
            return "(json-serialization failed: " + e.getMessage() + ")";
        }
    }

    @SuppressWarnings("unchecked")
    private Query buildQuery(String op, String field, DiscoverRequest.SpatialConstraint c) {
        try {
            if ("s_dwithin".equals(op)) {
                List<?> coords = (List<?>) c.getGeometry().getCoordinates();
                double lon = ((Number) coords.get(0)).doubleValue();
                double lat = ((Number) coords.get(1)).doubleValue();
                String radius = c.getDistanceMeters().longValue() + "m";

                Map<String, Object> circleShape = new LinkedHashMap<>();
                circleShape.put("type", "circle");
                circleShape.put("coordinates", List.of(lon, lat));
                circleShape.put("radius", radius);

                return buildGeoShapeQuery(field, circleShape, GeoShapeRelation.Intersects);
            } else {
                GeoShapeRelation relation = RELATIONS.get(op);
                Map<String, Object> geoJson = mapper.convertValue(c.getGeometry(), Map.class);
                return buildGeoShapeQuery(field, geoJson, relation);
            }
        } catch (Exception e) {
            log.warn("es.spatial.build-query.failed op={} field={} error={}", op, field, e.getMessage());
            return null;
        }
    }

    private static Query buildGeoShapeQuery(String field, Map<String, Object> shape,
                                             GeoShapeRelation relation) {
        return Query.of(q -> q.geoShape(gs -> gs
                .field(field)
                .shape(s -> s.relation(relation).shape(JsonData.of(shape)))
        ));
    }

    /**
     * Resolves targets (String or List<String>) → ES field name using path normalization.
     */
    private static String resolveFieldName(Object targets) {
        String path;
        if (targets instanceof List<?> list && !list.isEmpty()) {
            path = list.get(0).toString();
        } else if (targets instanceof String s) {
            path = s;
        } else {
            return null;
        }
        return toFieldName(path);
    }

    /**
     * Normalizes a full JSONPath to an ES field name.
     * Works for location fields anywhere in the payload — items, offers, or any custom field.
     *
     * Both v2.0-style paths (with beckn: prefix) and v2.1-style paths (plain) produce the
     * same ES field name, because catalog payloads are normalized before indexing.
     *
     * Input:  $.catalogs[*].beckn:items[*].beckn:availableAt[*].geo  (v2.0 subscriber path)
     * Input:  $.catalogs[*].items[*].availableAt[*].geo              (v2.1 subscriber path)
     * Output (both): loc_catalogs_items_availableAt
     *
     * Input:  $.catalogs[*].beckn:offers[*].beckn:location.geo  (v2.0)
     * Input:  $.catalogs[*].offers[*].location.geo              (v2.1)
     * Output (both): loc_catalogs_offers_location
     */
    static String toFieldName(String path) {
        if (path == null) return null;
        // Strip root $. prefix
        String rel = path.startsWith("$.") ? path.substring(2) : path.startsWith("$") ? path.substring(1) : path;
        // Strip .geo suffix
        if (rel.endsWith(".geo")) rel = rel.substring(0, rel.length() - 4);
        // Remove array wildcards
        rel = rel.replace("[*]", "");
        // Strip beckn: prefix from all path segments so v2.0 and v2.1 subscriber paths
        // resolve to the same ES field (catalog payloads are normalized before indexing)
        rel = rel.replace("beckn:", "");
        // Replace remaining . with _
        rel = rel.replace(".", "_");
        if (rel.isBlank()) return null;
        return "loc_" + rel;
    }
}
