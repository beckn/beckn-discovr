package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Provider DTO
 * 
 * Represents a provider that offers items in the catalog.
 */
public class Provider {

    @NotBlank(message = "beckn:id is required")
    @JsonProperty("beckn:id")
    private String id;

    @NotNull(message = "beckn:descriptor is required")
    @Valid
    @JsonProperty("beckn:descriptor")
    private Descriptor descriptor;

    @JsonProperty("beckn:validity")
    private TimePeriod validity;

    @JsonProperty("beckn:locations")
    private List<Location> locations;

    @JsonProperty("beckn:rateable")
    private Boolean rateable;

    @JsonProperty("beckn:rating")
    private Rating rating;

    @JsonProperty("beckn:providerAttributes")
    private Attributes providerAttributes;

    // Default constructor
    public Provider() {}

    // Constructor with required fields
    public Provider(String id, Descriptor descriptor) {
        this.id = id;
        this.descriptor = descriptor;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Descriptor getDescriptor() {
        return descriptor;
    }

    public void setDescriptor(Descriptor descriptor) {
        this.descriptor = descriptor;
    }

    public TimePeriod getValidity() {
        return validity;
    }

    public void setValidity(TimePeriod validity) {
        this.validity = validity;
    }

    public List<Location> getLocations() {
        return locations;
    }

    public void setLocations(List<Location> locations) {
        this.locations = locations;
    }

    public Boolean getRateable() {
        return rateable;
    }

    public void setRateable(Boolean rateable) {
        this.rateable = rateable;
    }

    public Rating getRating() {
        return rating;
    }

    public void setRating(Rating rating) {
        this.rating = rating;
    }

    public Attributes getProviderAttributes() {
        return providerAttributes;
    }

    public void setProviderAttributes(Attributes providerAttributes) {
        this.providerAttributes = providerAttributes;
    }

    @Override
    public String toString() {
        return "Provider{" +
                "id='" + id + '\'' +
                ", descriptor=" + descriptor +
                ", validity=" + validity +
                ", locations=" + locations +
                ", rateable=" + rateable +
                ", rating=" + rating +
                ", providerAttributes=" + providerAttributes +
                '}';
    }
}
