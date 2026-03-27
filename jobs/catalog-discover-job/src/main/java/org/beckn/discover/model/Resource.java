package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.beckn.discover.common.BecknFields;

import java.util.List;

/**
 * Resource DTO — Beckn Protocol v2.0.
 * Equivalent to Item but uses {@code resourceAttributes} instead of {@code itemAttributes}.
 * Used in catalogs that publish resources (e.g. timeslots, seats, subscriptions) rather than
 * physical/tangible items.
 */
public class Resource {

    @NotBlank(message = "id is required")
    @JsonProperty(BecknFields.ID)
    private String id;

    @NotNull(message = "descriptor is required")
    @Valid
    @JsonProperty(BecknFields.DESCRIPTOR)
    private Descriptor descriptor;

    @JsonProperty("category")
    private CategoryCode category;

    @JsonProperty("availableAt")
    private List<Location> availableAt;

    @JsonProperty("availabilityWindow")
    private List<TimePeriod> availabilityWindow;

    @JsonProperty("rateable")
    private Boolean rateable;

    @JsonProperty("rating")
    private Rating rating;

    @JsonProperty("isActive")
    private Boolean isActive;

    @NotNull(message = "provider is required")
    @Valid
    @JsonProperty(BecknFields.PROVIDER)
    private Provider provider;

    @NotNull(message = "resourceAttributes is required")
    @Valid
    @JsonProperty(BecknFields.RESOURCE_ATTRIBUTES)
    private Attributes resourceAttributes;

    @JsonProperty("constraints")
    private List<Constraint> constraints;

    @JsonProperty("policies")
    private List<Policy> policies;

    // Default constructor
    public Resource() {}

    // Constructor with required fields
    public Resource(String id, Descriptor descriptor, Provider provider, Attributes resourceAttributes) {
        this.id = id;
        this.descriptor = descriptor;
        this.provider = provider;
        this.resourceAttributes = resourceAttributes;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Descriptor getDescriptor() { return descriptor; }
    public void setDescriptor(Descriptor descriptor) { this.descriptor = descriptor; }

    public CategoryCode getCategory() { return category; }
    public void setCategory(CategoryCode category) { this.category = category; }

    public List<Location> getAvailableAt() { return availableAt; }
    public void setAvailableAt(List<Location> availableAt) { this.availableAt = availableAt; }

    public List<TimePeriod> getAvailabilityWindow() { return availabilityWindow; }
    public void setAvailabilityWindow(List<TimePeriod> availabilityWindow) { this.availabilityWindow = availabilityWindow; }

    public Boolean getRateable() { return rateable; }
    public void setRateable(Boolean rateable) { this.rateable = rateable; }

    public Rating getRating() { return rating; }
    public void setRating(Rating rating) { this.rating = rating; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }

    public Attributes getResourceAttributes() { return resourceAttributes; }
    public void setResourceAttributes(Attributes resourceAttributes) { this.resourceAttributes = resourceAttributes; }

    public List<Constraint> getConstraints() { return constraints; }
    public void setConstraints(List<Constraint> constraints) { this.constraints = constraints; }

    public List<Policy> getPolicies() { return policies; }
    public void setPolicies(List<Policy> policies) { this.policies = policies; }

    @Override
    public String toString() {
        return "Resource{id='" + id + "', provider=" + provider + ", resourceAttributes=" + resourceAttributes + '}';
    }
}
