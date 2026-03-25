package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * Policy DTO — Beckn Protocol v2.0.
 * A named policy applicable to an item or provider (e.g. cancellation, return).
 * Additional domain-specific properties are carried via additionalProperties.
 */
public class Policy {

    @JsonProperty("@type")
    private String type;

    @JsonProperty("name")
    private String name;

    @JsonProperty("descriptor")
    private Descriptor descriptor;

    @JsonProperty("validity")
    private TimePeriod validity;

    private Map<String, Object> additionalProperties = new HashMap<>();

    public Policy() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Descriptor getDescriptor() { return descriptor; }
    public void setDescriptor(Descriptor descriptor) { this.descriptor = descriptor; }

    public TimePeriod getValidity() { return validity; }
    public void setValidity(TimePeriod validity) { this.validity = validity; }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() { return additionalProperties; }

    @JsonAnySetter
    public void setAdditionalProperty(String key, Object val) {
        this.additionalProperties.put(key, val);
    }

    @Override
    public String toString() {
        return "Policy{type='" + type + "', name='" + name + "'}";
    }
}
