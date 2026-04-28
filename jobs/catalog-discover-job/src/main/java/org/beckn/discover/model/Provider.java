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
 * Provider DTO — Beckn Protocol v2.0.
 *
 * <p>Aligned with {@code components/schemas/Provider} in beckn.yaml:
 * {@code id} (required), {@code descriptor} (required), {@code availableAt},
 * {@code providerAttributes}. {@code additionalProperties: false} in the spec.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Provider {

    @NotBlank(message = "id is required")
    @JsonProperty(BecknFields.ID)
    private String id;

    @NotNull(message = "descriptor is required")
    @Valid
    @JsonProperty(BecknFields.DESCRIPTOR)
    private Descriptor descriptor;

    @JsonProperty("availableAt")
    private List<Location> availableAt;

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

    public List<Location> getAvailableAt() { return availableAt; }
    public void setAvailableAt(List<Location> availableAt) { this.availableAt = availableAt; }

    public Attributes getProviderAttributes() { return providerAttributes; }
    public void setProviderAttributes(Attributes providerAttributes) { this.providerAttributes = providerAttributes; }

    @Override
    public String toString() {
        return "Provider{id='" + id + "', descriptor=" + descriptor + '}';
    }
}
