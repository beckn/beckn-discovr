package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * Constraint DTO — Beckn Protocol v2.0.
 * Named constraint on an item (e.g. quantity limits, eligibility rules).
 * Additional domain-specific properties are carried via additionalProperties.
 */
public class Constraint {

    @JsonProperty("@type")
    private String type;

    @JsonProperty("name")
    private String name;

    @JsonProperty("value")
    private Object value;

    private Map<String, Object> additionalProperties = new HashMap<>();

    public Constraint() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() { return additionalProperties; }

    @JsonAnySetter
    public void setAdditionalProperty(String key, Object val) {
        this.additionalProperties.put(key, val);
    }

    @Override
    public String toString() {
        return "Constraint{type='" + type + "', name='" + name + "', value=" + value + '}';
    }
}
