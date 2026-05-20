package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.beckn.discover.common.BecknFields;

import java.util.List;

/**
 * Catalog DTO — Beckn Protocol v2.0 (no beckn: prefix on field names).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties("items")
public class Catalog {

    @NotBlank(message = "id is required")
    @JsonProperty(BecknFields.ID)
    private String id;

    @NotNull(message = "descriptor is required")
    @Valid
    @JsonProperty(BecknFields.DESCRIPTOR)
    private Descriptor descriptor;

    @JsonIgnore
    private String providerId;

    @Valid
    @JsonProperty(BecknFields.PROVIDER)
    private Provider provider;

    @JsonProperty(BecknFields.BPP_ID)
    private String bppId;

    @JsonProperty(BecknFields.BPP_URI)
    private String bppUri;

    @JsonProperty("validity")
    private TimePeriod validity;

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

    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }

    public String getBppId() { return bppId; }
    public void setBppId(String bppId) { this.bppId = bppId; }

    public String getBppUri() { return bppUri; }
    public void setBppUri(String bppUri) { this.bppUri = bppUri; }

    public TimePeriod getValidity() { return validity; }
    public void setValidity(TimePeriod validity) { this.validity = validity; }

    public List<Resource> getResources() { return resources; }
    public void setResources(List<Resource> resources) { this.resources = resources; }

    public List<Object> getOffers() { return offers; }
    public void setOffers(List<Object> offers) { this.offers = offers; }

    @Override
    public String toString() {
        return "Catalog{id='" + id + "', bppId='" + bppId + "', resources=" + resources + '}';
    }
}
