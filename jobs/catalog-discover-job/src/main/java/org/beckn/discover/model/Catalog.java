package org.beckn.discover.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.beckn.discover.common.BecknFields;

import java.util.List;

/**
 * Catalog DTO — Beckn Protocol v2.0 (no beckn: prefix on field names).
 */
public class Catalog {

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

    @JsonProperty(BecknFields.PROVIDER_ID)
    private String providerId;

    @JsonProperty(BecknFields.BPP_ID)
    private String bppId;

    @JsonProperty(BecknFields.BPP_URI)
    private String bppUri;

    @JsonProperty("validity")
    private TimePeriod validity;

    @Valid
    @JsonProperty(BecknFields.ITEMS)
    private List<Item> items;

    @Valid
    @JsonProperty(BecknFields.RESOURCES)
    private List<Resource> resources;

    @JsonProperty(BecknFields.OFFERS)
    private List<Object> offers;

    // Default constructor
    public Catalog() {}

    // Constructor with required fields
    public Catalog(String context, String type, String id, Descriptor descriptor, List<Item> items) {
        this.context = context;
        this.type = type;
        this.id = id;
        this.descriptor = descriptor;
        this.items = items;
    }

    /** Returns a shallow copy of this catalog with the given items list. */
    public Catalog copyWithItems(List<Item> newItems) {
        Catalog copy = new Catalog();
        copy.context = this.context;
        copy.type = this.type;
        copy.id = this.id;
        copy.descriptor = this.descriptor;
        copy.providerId = this.providerId;
        copy.bppId = this.bppId;
        copy.bppUri = this.bppUri;
        copy.validity = this.validity;
        copy.offers = this.offers;
        copy.items = newItems;
        copy.resources = this.resources;
        return copy;
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

    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }

    public String getBppId() { return bppId; }
    public void setBppId(String bppId) { this.bppId = bppId; }

    public String getBppUri() { return bppUri; }
    public void setBppUri(String bppUri) { this.bppUri = bppUri; }

    public TimePeriod getValidity() { return validity; }
    public void setValidity(TimePeriod validity) { this.validity = validity; }

    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }

    public List<Resource> getResources() { return resources; }
    public void setResources(List<Resource> resources) { this.resources = resources; }

    public List<Object> getOffers() { return offers; }
    public void setOffers(List<Object> offers) { this.offers = offers; }

    @Override
    public String toString() {
        return "Catalog{id='" + id + "', bppId='" + bppId + "', items=" + items + '}';
    }
}
