package org.beckn.catalogpublish.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for the {@code provider_offer} table: (offerId, catalogId).
 */
public class ProviderOfferId implements Serializable {

    private String offerId;
    private String catalogId;

    public ProviderOfferId() {}

    public ProviderOfferId(String offerId, String catalogId) {
        this.offerId = offerId;
        this.catalogId = catalogId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProviderOfferId other)) return false;
        return Objects.equals(offerId, other.offerId) && Objects.equals(catalogId, other.catalogId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(offerId, catalogId);
    }
}
