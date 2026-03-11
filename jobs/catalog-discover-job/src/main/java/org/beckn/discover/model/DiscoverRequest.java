package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Discover Request DTO
 * 
 * Represents the complete discovery request containing context and message with
 * search intent.
 * Based on schema.json DiscoverRequest structure.
 * 
 * @version 2.0.0
 */
public class DiscoverRequest {

    @NotNull(message = "Context is required")
    @Valid
    @JsonProperty("context")
    private Context context;

    // New schema: wrap search parameters under "message"
    @JsonProperty("message")
    private Message message;

    // Back-compat fields (root-level), will be populated when no message object is
    // present
    @JsonProperty("text_search")
    private String textSearch;

    @JsonProperty("filters")
    private String filters;

    @JsonProperty("spatial")
    private List<SpatialConstraint> spatial;

    // Default constructor
    public DiscoverRequest() {
    }

    // Constructor with required fields
    public DiscoverRequest(Context context) {
        this.context = context;
    }

    // Getters and Setters
    public Context getContext() {
        return context;
    }

    public void setContext(Context context) {
        this.context = context;
    }

    public Message getMessage() {
        return message;
    }

    public void setMessage(Message message) {
        this.message = message;
    }

    public String getTextSearch() {
        if (message != null && message.getTextSearch() != null) {
            return message.getTextSearch();
        }
        return textSearch;
    }

    public void setTextSearch(String textSearch) {
        // Populate both for symmetry; serializer can choose message-first via getter
        if (this.message == null)
            this.message = new Message();
        this.message.setTextSearch(textSearch);
        this.textSearch = textSearch;
    }

    public String getFilters() {
        if (message != null && message.getFilters() != null) {
            return message.getFilters().getExpression();
        }
        return filters;
    }

    public void setFilters(String filters) {
        if (this.message == null)
            this.message = new Message();
        Filter filterObj = new Filter();
        filterObj.setType("jsonpath");
        filterObj.setExpression(filters);
        this.message.setFilters(filterObj);
        this.filters = filters;
    }

    public List<SpatialConstraint> getSpatial() {
        if (message != null && message.getSpatial() != null) {
            return message.getSpatial();
        }
        return spatial;
    }

    public void setSpatial(List<SpatialConstraint> spatial) {
        if (this.message == null)
            this.message = new Message();
        this.message.setSpatial(spatial);
        this.spatial = spatial;
    }

    @Override
    public String toString() {
        return "DiscoverRequest{" +
                "context=" + context +
                ", message=" + message +
                ", textSearch='" + textSearch + '\'' +
                ", filters='" + filters + '\'' +
                '}';
    }

    // New schema message container
    public static class Message {
        @JsonProperty("text_search")
        private String textSearch;

        @JsonProperty("filters")
        private Filter filters;

        @JsonProperty("spatial")
        private List<SpatialConstraint> spatial;

        public Message() {
        }

        public String getTextSearch() {
            return textSearch;
        }

        public void setTextSearch(String textSearch) {
            this.textSearch = textSearch;
        }

        public Filter getFilters() {
            return filters;
        }

        public void setFilters(Filter filters) {
            this.filters = filters;
        }

        public List<SpatialConstraint> getSpatial() {
            return spatial;
        }

        public void setSpatial(List<SpatialConstraint> spatial) {
            this.spatial = spatial;
        }
    }

    /**
     * Spatial Constraint for geo-spatial queries
     */
    public static class SpatialConstraint {
        @JsonProperty("op")
        private String operation; // s_dwithin, s_intersects, etc.

        @JsonProperty("targets")
        private Object targets; // String or List<String> for JSONPath pointers

        @JsonProperty("geometry")
        private GeoJSONGeometry geometry;

        @JsonProperty("distanceMeters")
        private Double distanceMeters; // For s_dwithin operations

        @JsonProperty("quantifier")
        private String quantifier = "any"; // any, all, none

        public SpatialConstraint() {
        }

        public String getOperation() {
            return operation;
        }

        public void setOperation(String operation) {
            this.operation = operation;
        }

        public Object getTargets() {
            return targets;
        }

        public void setTargets(Object targets) {
            this.targets = targets;
        }

        public GeoJSONGeometry getGeometry() {
            return geometry;
        }

        public void setGeometry(GeoJSONGeometry geometry) {
            this.geometry = geometry;
        }

        public Double getDistanceMeters() {
            return distanceMeters;
        }

        public void setDistanceMeters(Double distanceMeters) {
            this.distanceMeters = distanceMeters;
        }

        public String getQuantifier() {
            return quantifier;
        }

        public void setQuantifier(String quantifier) {
            this.quantifier = quantifier;
        }
    }

    /**
     * GeoJSON Geometry for spatial queries
     */
    public static class GeoJSONGeometry {
        @JsonProperty("type")
        private String type; // Point, LineString, Polygon, etc.

        @JsonProperty("coordinates")
        private Object coordinates; // Varies by geometry type

        @JsonProperty("geometries")
        private List<GeoJSONGeometry> geometries; // For GeometryCollection

        public GeoJSONGeometry() {
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Object getCoordinates() {
            return coordinates;
        }

        public void setCoordinates(Object coordinates) {
            this.coordinates = coordinates;
        }

        public List<GeoJSONGeometry> getGeometries() {
            return geometries;
        }

        public void setGeometries(List<GeoJSONGeometry> geometries) {
            this.geometries = geometries;
        }
    }

    /**
     * Filter object for JSONPath expressions
     */
    public static class Filter {
        @JsonProperty("type")
        private String type;

        @JsonProperty("expression")
        private String expression;

        public Filter() {
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getExpression() {
            return expression;
        }

        public void setExpression(String expression) {
            this.expression = expression;
        }
    }

}
