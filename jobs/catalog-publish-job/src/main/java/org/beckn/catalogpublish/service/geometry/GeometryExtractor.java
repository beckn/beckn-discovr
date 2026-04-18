package org.beckn.catalogpublish.service.geometry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.model.ItemLocationCollection;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts all geometry values from a denormalized item payload and returns them with absolute paths.
 * Traverses every property in the JSON tree (objects and arrays), and for each occurrence of
 * {@code gps}, {@code geo} (GeoJSON Point/Polygon), or {@code polygon}, records
 * (itemId, absolutePath, geom) for storage in {@code item_location_collection}.
 * Paths use JSONPath-style with {@code [*]} for array indices so they match discovery API targets,
 * e.g. {@code $.catalogs[*].resources[*].availableAt[*].geo}.
 */
@Service
public class GeometryExtractor {

    private static final Logger log = LoggerFactory.getLogger(GeometryExtractor.class);
    private static final int TRUNCATE_LEN = 50;
    private static final int MAX_DEPTH = 32;
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @FunctionalInterface
    private interface GeometryParser {
        Optional<Geometry> parse(JsonNode value, String itemId, String catalogId, String path);
    }

    /**
     * One entry per supported geometry format.
     * Drives both dispatch in walkJsonTree and the skip-recursion guard — single source of truth.
     */
    private static final Map<String, GeometryParser> GEOMETRY_PARSERS = Map.of(
            "gps",     (n, id, catId, p) -> tryParseGps(n.asText(), id, p),
            "geo",     (n, id, catId, p) -> tryParseGeoJson(n, id, p),
            "polygon", (n, id, catId, p) -> tryParsePolygon(n, id, p)
    );

    private final ObjectMapper objectMapper;

    public GeometryExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Traverses the entire payload from root, finds every gps/geo/polygon at any depth,
     * and returns one entry per geometry with its absolute path from root.
     * Accepts a pre-parsed JsonNode to avoid redundant deserialization when the caller
     * already holds the payload as a JsonNode.
     */
    public List<ItemLocationCollection> extractLocations(String itemId, String catalogId, JsonNode payloadRoot) {
        try {
            List<ItemLocationCollection> result = new ArrayList<>();
            walkJsonTree(itemId, catalogId, payloadRoot, "$", 0, result);
            return List.copyOf(result);
        } catch (Exception e) {
            log.warn("event={} itemId={}", LogEvent.GEO_EXTRACT_FAILED, itemId);
            return List.of();
        }
    }

    /** Convenience overload — parses the payload JSON string before walking the tree. */
    public List<ItemLocationCollection> extractLocations(String itemId, String catalogId, String payloadJson) {
        try {
            return extractLocations(itemId, catalogId, objectMapper.readTree(payloadJson));
        } catch (Exception e) {
            log.warn("event={} itemId={} error={}", LogEvent.GEO_EXTRACT_PARSE_FAILED, itemId, e.getMessage());
            return List.of();
        }
    }

    private void walkJsonTree(String itemId, String catalogId, JsonNode node, String path, int depth,
            List<ItemLocationCollection> accumulator) {
        if (node == null || node.isMissingNode()) return;
        if (depth > MAX_DEPTH) {
            log.warn("event={} itemId={} path={}", LogEvent.GEO_MAX_DEPTH_EXCEEDED, itemId, path);
            return;
        }
        if (node.isObject()) {
            // Single pass over all fields: geometry keys are dispatched to their parser,
            // all other keys are recursed into. Previously two separate iterations.
            node.fields().forEachRemaining(entry -> {
                String key     = entry.getKey();
                String segPath = pathSegment(path, key);
                GeometryParser parser = GEOMETRY_PARSERS.get(key);
                if (parser != null) {
                    parser.parse(entry.getValue(), itemId, catalogId, segPath)
                            .map(geom -> new ItemLocationCollection(itemId, catalogId, segPath, geom))
                            .ifPresent(accumulator::add);
                } else {
                    walkJsonTree(itemId, catalogId, entry.getValue(), segPath, depth + 1, accumulator);
                }
            });
        }
        if (node.isArray()) {
            // Always use [*] so stored paths match the discovery API's JSONPath wildcard queries.
            for (int i = 0; i < node.size(); i++)
                walkJsonTree(itemId, catalogId, node.get(i), path + "[*]", depth + 1, accumulator);
        }
    }

    /**
     * JSONPath-style segment: path + "." + key.
     * Example: "$" → "$.catalogs", "$.catalogs[*]" → "$.catalogs[*].resources".
     */
    private static String pathSegment(String path, String key) {
        return "$".equals(path) ? "$." + key : path + "." + key;
    }

    private static Optional<Geometry> tryParseGps(String gps, String itemId, String path) {
        try {
            if (gps == null || gps.isBlank()) return Optional.empty();
            String[] parts = gps.strip().split(",", 2);
            if (parts.length != 2) return Optional.empty();
            double lat = Double.parseDouble(parts[0].strip());
            double lon = Double.parseDouble(parts[1].strip());
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                log.warn("event={} itemId={} path={} gps={}", LogEvent.GEO_GPS_OUT_OF_RANGE, itemId, path, truncate(gps, TRUNCATE_LEN));
                return Optional.empty();
            }
            return Optional.of(GEOMETRY_FACTORY.createPoint(new Coordinate(lon, lat)));
        } catch (NumberFormatException e) {
            log.warn("event={} itemId={} path={} gps={}", LogEvent.GEO_GPS_PARSE_FAILED, itemId, path, truncate(gps, TRUNCATE_LEN));
            return Optional.empty();
        }
    }

    /**
     * Parse GeoJSON geometry (RFC 7946). Supports Point and Polygon.
     * Point:   {"type":"Point","coordinates":[lon, lat]}
     * Polygon: {"type":"Polygon","coordinates":[[[lon,lat],...]]}
     */
    private static Optional<Geometry> tryParseGeoJson(JsonNode geo, String itemId, String path) {
        try {
            if (geo == null || !geo.isObject()) return Optional.empty();
            String type = geo.has("type") ? geo.get("type").asText(null) : null;
            if (type == null || type.isBlank()) return Optional.empty();
            JsonNode coords = geo.get("coordinates");
            if (coords == null || coords.isMissingNode()) return Optional.empty();
            return switch (type) {
                case "Point"   -> tryParseGeoJsonPoint(coords, itemId, path);
                case "Polygon" -> (coords.isArray() && !coords.isEmpty())
                        ? ringToPolygon(coords.get(0))
                        : Optional.empty();
                default        -> {
                    log.debug("event={} itemId={} path={} type={}", LogEvent.GEO_GEOJSON_UNSUPPORTED_TYPE, itemId, path, type);
                    yield Optional.empty();
                }
            };
        } catch (Exception e) {
            log.warn("event={} itemId={} path={} error={}", LogEvent.GEO_GEOJSON_PARSE_FAILED, itemId, path, e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<Geometry> tryParseGeoJsonPoint(JsonNode coordinates, String itemId, String path) {
        if (!coordinates.isArray() || coordinates.size() < 2) return Optional.empty();
        double lon = coordinates.get(0).asDouble();
        double lat = coordinates.get(1).asDouble();
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            log.warn("event={} itemId={} path={} lon={} lat={}", LogEvent.GEO_GEOJSON_POINT_OUT_OF_RANGE, itemId, path, lon, lat);
            return Optional.empty();
        }
        return Optional.of(GEOMETRY_FACTORY.createPoint(new Coordinate(lon, lat)));
    }

    /**
     * Parses a flat coordinate ring: an array of [lon, lat] pairs (minimum 4 for linear-ring closure).
     * Shared by GeoJSON {@code Polygon} and the custom {@code polygon} field format — eliminates duplication.
     */
    private static Optional<Geometry> ringToPolygon(JsonNode ring) {
        if (ring == null || !ring.isArray() || ring.size() < 4) return Optional.empty();
        Coordinate[] coords = new Coordinate[ring.size()];
        for (int i = 0; i < ring.size(); i++) {
            JsonNode p = ring.get(i);
            if (!p.isArray() || p.size() < 2) return Optional.empty();
            coords[i] = new Coordinate(p.get(0).asDouble(), p.get(1).asDouble());
        }
        return Optional.of(GEOMETRY_FACTORY.createPolygon(GEOMETRY_FACTORY.createLinearRing(coords)));
    }

    private static Optional<Geometry> tryParsePolygon(JsonNode polygonNode, String itemId, String path) {
        try {
            return ringToPolygon(polygonNode);
        } catch (Exception e) {
            log.warn("event={} itemId={} path={} error={}", LogEvent.GEO_POLYGON_PARSE_FAILED, itemId, path, e.getMessage());
            return Optional.empty();
        }
    }

    private static String truncate(String value, int maxLen) {
        return (value == null || value.length() <= maxLen) ? value : value.substring(0, maxLen) + "...";
    }
}
