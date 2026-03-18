package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import org.beckn.discover.common.BecknFields;

import java.util.HashMap;
import java.util.Map;

/**
 * Attributes DTO — Beckn Protocol v2.0.
 * Uses plain {@code context} and {@code type} field names (no {@code @} prefix).
 * Any additional domain-specific properties are allowed via {@code additionalProperties}.
 */
public class Attributes {

    @NotBlank(message = "context is required")
    @JsonProperty(BecknFields.CONTEXT)
    private String context;

    @NotBlank(message = "type is required")
    @JsonProperty(BecknFields.TYPE)
    private String type;

    // Additional properties are stored in a map to handle dynamic attributes
    private Map<String, Object> additionalProperties = new HashMap<>();

    // Default constructor
    public Attributes() {}

    // Constructor with required fields
    public Attributes(String context, String type) {
        this.context = context;
        this.type = type;
    }

    // Constructor with all fields
    public Attributes(String context, String type, Map<String, Object> additionalProperties) {
        this.context = context;
        this.type = type;
        this.additionalProperties = additionalProperties;
    }

    // Getters and Setters
    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    public void setAdditionalProperties(Map<String, Object> additionalProperties) {
        this.additionalProperties = additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String key, Object value) {
        this.additionalProperties.put(key, value);
    }

    // Helper method to get a specific attribute value
    public Object getAttribute(String key) {
        return additionalProperties != null ? additionalProperties.get(key) : null;
    }

    // Helper method to set a specific attribute value
    public void setAttribute(String key, Object value) {
        if (additionalProperties == null) {
            additionalProperties = new java.util.HashMap<>();
        }
        additionalProperties.put(key, value);
    }

    @Override
    public String toString() {
        return "Attributes{" +
                "context='" + context + '\'' +
                ", type='" + type + '\'' +
                ", additionalProperties=" + additionalProperties +
                '}';
    }
}
