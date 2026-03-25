package org.beckn.catalogpublish.service.geometry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Walks the entire item payload JSON tree and extracts all location geometry fields
 * (geo, gps, polygon) as raw GeoJSON Maps for Elasticsearch geo_shape indexing.
 *
 * Unlike {@link GeometryExtractor} (which converts to JTS for PostGIS), this class
 * performs raw passthrough — all GeoJSON types (Point, LineString, Polygon, Multi*,
 * GeometryCollection) are preserved without conversion.
 *
 * Output: map of loc_{key} → {"geo": [GeoJSON, ...]} ready to merge into an ES document.
 */
@Component
public class GeoShapeExtractor {

    private static final Logger log = LoggerFactory.getLogger(GeoShapeExtractor.class);
    private static final int MAX_DEPTH = 32;

    private final ObjectMapper objectMapper;

    public GeoShapeExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Extracts all location geometry fields from the payload and returns them as
     * ES-ready loc_* fields.
     *
     * @param payloadNode the full item payload JSON tree
     * @return map of "loc_beckn_availableAt" → {"geo": [GeoJSON, ...]}
     */
    /**
     * Returns loc_* → Location object (single) or List of Location objects (multiple).
     * Each Location object contains geo, address, @type as-is from the payload.
     * loc_*.geo matches the *.geo dynamic template → geo_shape for spatial queries.
     */
    public Map<String, Object> extractGeoShapes(JsonNode payloadNode) {
        Map<String, List<Object>> accumulator = new LinkedHashMap<>();
        walkJsonTree(payloadNode, "$", 0, accumulator);

        Map<String, Object> result = new LinkedHashMap<>();
        accumulator.forEach((fieldName, locations) ->
                result.put(fieldName, locations.size() == 1 ? locations.get(0) : locations));
        return result;
    }

    private void walkJsonTree(JsonNode node, String path, int depth,
                              Map<String, List<Object>> accumulator) {
        if (node == null || node.isMissingNode()) return;
        if (depth > MAX_DEPTH) {
            log.warn("geo-shape.max-depth-exceeded path={}", path);
            return;
        }
        if (node.isObject()) {
            // If this object IS a Location (contains geo/gps/polygon), store the full object
            // and stop recursing — handles any key name at any depth
            if (node.has("geo") || node.has("gps") || node.has("polygon")) {
                if (isValidLocation(node)) {
                    Map<String, Object> locationObj = objectMapper.convertValue(node, Map.class);
                    accumulate(path, locationObj, accumulator);
                }
                return; // don't recurse into the location object itself
            }
            // Not a location — recurse into all fields
            node.fields().forEachRemaining(entry -> {
                String segPath = pathSegment(path, entry.getKey());
                walkJsonTree(entry.getValue(), segPath, depth + 1, accumulator);
            });
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++)
                walkJsonTree(node.get(i), path + "[*]", depth + 1, accumulator);
        }
    }

    /**
     * Validates that the node is a meaningful location object:
     * - has a valid geo (GeoJSON with type), OR
     * - has a non-blank gps string, OR
     * - has a polygon array
     */
    private static boolean isValidLocation(JsonNode node) {
        if (node.has("geo")) {
            JsonNode geo = node.get("geo");
            return geo.isObject() && geo.has("type");
        }
        if (node.has("gps")) {
            String gps = node.get("gps").asText("");
            return !gps.isBlank() && gps.contains(",");
        }
        if (node.has("polygon")) {
            return node.get("polygon").isArray();
        }
        return false;
    }

    private void accumulate(String path, Map<String, Object> locationObj,
                            Map<String, List<Object>> accumulator) {
        String fieldName = toFieldName(path);
        if (fieldName != null)
            accumulator.computeIfAbsent(fieldName, k -> new ArrayList<>()).add(locationObj);
    }

    /**
     * Converts "lat,lon" GPS string to GeoJSON Point.
     */
    private static Optional<Map<String, Object>> gpsToGeoJson(String gps, String path) {
        try {
            if (gps == null || gps.isBlank()) return Optional.empty();
            String[] parts = gps.strip().split(",", 2);
            if (parts.length != 2) return Optional.empty();
            double lat = Double.parseDouble(parts[0].strip());
            double lon = Double.parseDouble(parts[1].strip());
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                log.warn("geo-shape.gps.out-of-range path={} gps={}", path, gps);
                return Optional.empty();
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("type", "Point");
            point.put("coordinates", List.of(lon, lat));
            return Optional.of(point);
        } catch (NumberFormatException e) {
            log.warn("geo-shape.gps.parse-failed path={} gps={}", path, gps);
            return Optional.empty();
        }
    }

    /**
     * Converts polygon array ([[lon,lat],...]) to GeoJSON Polygon.
     */
    private static Optional<Map<String, Object>> polygonToGeoJson(JsonNode polygon, String path) {
        try {
            if (!polygon.isArray() || polygon.size() < 4) return Optional.empty();
            List<List<Double>> ring = new ArrayList<>();
            for (JsonNode point : polygon) {
                if (!point.isArray() || point.size() < 2) return Optional.empty();
                ring.add(List.of(point.get(0).asDouble(), point.get(1).asDouble()));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "Polygon");
            result.put("coordinates", List.of(ring));
            return Optional.of(result);
        } catch (Exception e) {
            log.warn("geo-shape.polygon.parse-failed path={}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Normalizes a full JSON path to an ES field name.
     * Works for location fields anywhere in the payload — items, offers, or any custom field.
     *
     * Input:  $.catalogs[*].resources[*].availableAt[*].geo
     * Output: loc_catalogs_resources_availableAt
     *
     * Input:  $.catalogs[*].offers[*].location.geo
     * Output: loc_catalogs_offers_location
     */
    static String toFieldName(String path) {
        if (path == null) return null;
        // Strip root $. prefix
        String rel = path.startsWith("$.") ? path.substring(2) : path.startsWith("$") ? path.substring(1) : path;
        // Strip .geo suffix (the geo field itself — we store at parent level)
        if (rel.endsWith(".geo")) rel = rel.substring(0, rel.length() - 4);
        // Remove array wildcards
        rel = rel.replace("[*]", "");
        // Replace : and . with _
        rel = rel.replace(":", "_").replace(".", "_");
        if (rel.isBlank()) return null;
        return "loc_" + rel;
    }

    private static String pathSegment(String path, String key) {
        return "$".equals(path) ? "$." + key : path + "." + key;
    }
}
