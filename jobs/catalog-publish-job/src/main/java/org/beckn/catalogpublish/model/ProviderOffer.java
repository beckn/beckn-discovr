package org.beckn.catalogpublish.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Provider-level offer: an offer published without {@code resourceIds}.
 * Stored once per (offer_id, catalog_id) — resolved at search time by provider_id lookup.
 */
@Entity
@Table(name = "provider_offer")
@IdClass(ProviderOfferId.class)
public class ProviderOffer {

    @Id
    @Column(name = "offer_id", nullable = false)
    private String offerId;

    @Id
    @Column(name = "catalog_id", nullable = false)
    private String catalogId;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "subscriber_id")
    private String subscriberId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected ProviderOffer() {}

    public static ProviderOffer from(String offerId, String catalogId, String providerId,
            String payload, String recordId, String subscriberId) {
        var po = new ProviderOffer();
        po.offerId = offerId;
        po.catalogId = catalogId;
        po.providerId = providerId;
        po.payload = payload;
        var effectiveOwner = (recordId != null && !recordId.isBlank()) ? recordId : subscriberId;
        po.createdBy = effectiveOwner;
        po.updatedBy = effectiveOwner;
        po.subscriberId = subscriberId;
        var now = LocalDateTime.now();
        po.createdAt = now;
        po.updatedAt = now;
        return po;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProviderOffer other)) return false;
        return Objects.equals(offerId, other.offerId) && Objects.equals(catalogId, other.catalogId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(offerId, catalogId);
    }

    public String getOfferId() { return offerId; }
    public String getCatalogId() { return catalogId; }
    public String getProviderId() { return providerId; }
    public String getPayload() { return payload; }
    public String getCreatedBy() { return createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public String getSubscriberId() { return subscriberId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
