package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.beckn.discover.common.BecknFields;

import java.util.List;

/**
 * Catalog DTO — Beckn Protocol v2.0.
 *
 * <p>Aligned with {@code components/schemas/Catalog} in beckn.yaml:
 * {@code id}, {@code descriptor}, {@code provider} (full Provider object),
 * {@code resources[]}, {@code offers[]}, {@code validity}, {@code isActive}.
 * {@code additionalProperties: false} in the spec.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Catalog {

    @NotBlank(message = "id is required")
    @JsonProperty(BecknFields.ID)
    private String id;

    @NotNull(message = "descriptor is required")
    @Valid
    @JsonProperty(BecknFields.DESCRIPTOR)
    private Descriptor descriptor;

    @Valid
    @JsonProperty(BecknFields.PROVIDER)
    private Provider provider;

    @JsonProperty("validity")
    private TimePeriod validity;

    @JsonProperty("isActive")
    private Boolean isActive;

    @Valid
    @JsonProperty(BecknFields.RESOURCES)
    private List<Resource> resources;

    @JsonProperty(BecknFields.OFFERS)
    private List<Object> offers;

    // Default constructor
    public Catalog() {}

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Descriptor getDescriptor() { return descriptor; }
    public void setDescriptor(Descriptor descriptor) { this.descriptor = descriptor; }

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }

    public TimePeriod getValidity() { return validity; }
    public void setValidity(TimePeriod validity) { this.validity = validity; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public List<Resource> getResources() { return resources; }
    public void setResources(List<Resource> resources) { this.resources = resources; }

    public List<Object> getOffers() { return offers; }
    public void setOffers(List<Object> offers) { this.offers = offers; }

    @Override
    public String toString() {
        return "Catalog{id='" + id + "', provider=" + provider + ", resources=" + resources + '}';
    }
}
