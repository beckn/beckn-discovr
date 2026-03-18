package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.beckn.discover.common.BecknFields;

import java.util.List;

/**
 * Provider DTO — Beckn Protocol v2.0 (no beckn: prefix on field names).
 */
public class Provider {

    @NotBlank(message = "id is required")
    @JsonProperty(BecknFields.ID)
    private String id;

    @NotNull(message = "descriptor is required")
    @Valid
    @JsonProperty(BecknFields.DESCRIPTOR)
    private Descriptor descriptor;

    @JsonProperty("validity")
    private TimePeriod validity;

    @JsonProperty("locations")
    private List<Location> locations;

    @JsonProperty("rateable")
    private Boolean rateable;

    @JsonProperty("rating")
    private Rating rating;

    @JsonProperty("providerAttributes")
    private Attributes providerAttributes;

    // Default constructor
    public Provider() {}

    // Constructor with required fields
    public Provider(String id, Descriptor descriptor) {
        this.id = id;
        this.descriptor = descriptor;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Descriptor getDescriptor() { return descriptor; }
    public void setDescriptor(Descriptor descriptor) { this.descriptor = descriptor; }

    public TimePeriod getValidity() { return validity; }
    public void setValidity(TimePeriod validity) { this.validity = validity; }

    public List<Location> getLocations() { return locations; }
    public void setLocations(List<Location> locations) { this.locations = locations; }

    public Boolean getRateable() { return rateable; }
    public void setRateable(Boolean rateable) { this.rateable = rateable; }

    public Rating getRating() { return rating; }
    public void setRating(Rating rating) { this.rating = rating; }

    public Attributes getProviderAttributes() { return providerAttributes; }
    public void setProviderAttributes(Attributes providerAttributes) { this.providerAttributes = providerAttributes; }

    @Override
    public String toString() {
        return "Provider{id='" + id + "', descriptor=" + descriptor + '}';
    }
}
