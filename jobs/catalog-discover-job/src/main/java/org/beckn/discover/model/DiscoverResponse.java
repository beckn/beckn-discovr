package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Discover Response DTO
 * 
 * Represents the discovery response containing context and message with catalogs.
 * Based on schema.json DiscoverResponse structure.
 * 
 * @version 2.0.0
 */
public class DiscoverResponse {

    @NotNull(message = "Context is required")
    @Valid
    @JsonProperty("context")
    private Context context;

    @NotNull(message = "Message is required")
    @Valid
    @JsonProperty("message")
    private ResponseMessage message;

    // No root-level catalogs in new schema; catalogs live under message

    // Default constructor
    public DiscoverResponse() {}

    // Constructor with required fields
    public DiscoverResponse(Context context, ResponseMessage message) {
        this.context = context;
        this.message = message;
    }

    // Remove legacy constructor that accepted root-level catalogs

    // Getters and Setters
    public Context getContext() {
        return context;
    }

    public void setContext(Context context) {
        this.context = context;
    }

    public ResponseMessage getMessage() {
        return message;
    }

    public void setMessage(ResponseMessage message) {
        this.message = message;
    }

    /** Convenience accessor: returns {@code message.getCatalogs()} or null. */
    @JsonIgnore
    public List<Catalog> getCatalogs() {
        return message != null ? message.getCatalogs() : null;
    }

    @Override
    public String toString() {
        return "DiscoverResponse{" +
                "context=" + context +
                ", message=" + message +
                '}';
    }

    /**
     * Response Message DTO containing catalogs
     */
    public static class ResponseMessage {
        @JsonProperty("catalogs")
        private List<Catalog> catalogs;

        public ResponseMessage() {}

        public ResponseMessage(List<Catalog> catalogs) {
            this.catalogs = catalogs;
        }

        public List<Catalog> getCatalogs() {
            return catalogs;
        }

        public void setCatalogs(List<Catalog> catalogs) {
            this.catalogs = catalogs;
        }

        @Override
        public String toString() {
            return "ResponseMessage{" +
                    "catalogs=" + catalogs +
                    '}';
        }
    }
}
