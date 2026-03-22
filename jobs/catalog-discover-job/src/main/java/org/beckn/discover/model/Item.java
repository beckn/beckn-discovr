package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.beckn.discover.common.BecknFields;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Item DTO — Beckn Protocol v2.0 (no beckn: prefix on field names).
 */
public class Item {

    @JsonProperty("@context")
    private String context;

    @JsonProperty("@type")
    private String type;

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

    @JsonProperty(BecknFields.NETWORK_ID)
    private String networkId;

    @JsonProperty("acceptedPaymentMethod")
    private List<String> acceptedPaymentMethod;

    @NotNull(message = "provider is required")
    @Valid
    @JsonProperty(BecknFields.PROVIDER)
    private Provider provider;

    @NotNull(message = "itemAttributes is required")
    @Valid
    @JsonProperty(BecknFields.ITEM_ATTRIBUTES)
    private Attributes itemAttributes;

    @JsonProperty("constraints")
    private List<Map<String, Object>> constraints;

    @JsonProperty("policies")
    private List<Map<String, Object>> policies;

    // Default constructor
    public Item() {}

    // Constructor with required fields
    public Item(String context, String type, String id, Descriptor descriptor, Provider provider,
            Attributes itemAttributes) {
        this.context = context;
        this.type = type;
        this.id = id;
        this.descriptor = descriptor;
        this.provider = provider;
        this.itemAttributes = itemAttributes;
    }

    // Getters and Setters
    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

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

    public String getNetworkId() { return networkId; }
    public void setNetworkId(String networkId) { this.networkId = networkId; }

    public List<String> getAcceptedPaymentMethod() { return acceptedPaymentMethod; }
    public void setAcceptedPaymentMethod(List<String> acceptedPaymentMethod) { this.acceptedPaymentMethod = acceptedPaymentMethod; }

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }

    public Attributes getItemAttributes() { return itemAttributes; }
    public void setItemAttributes(Attributes itemAttributes) { this.itemAttributes = itemAttributes; }

    public List<Map<String, Object>> getConstraints() { return constraints; }
    public void setConstraints(List<Map<String, Object>> constraints) { this.constraints = constraints; }

    public List<Map<String, Object>> getPolicies() { return policies; }
    public void setPolicies(List<Map<String, Object>> policies) { this.policies = policies; }

    @Override
    public String toString() {
        return "Item{id='" + id + "', provider=" + provider + ", itemAttributes=" + itemAttributes + '}';
    }
}
