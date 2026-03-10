package org.beckn.catalogpublish.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.beckn.catalogpublish.dto.CatalogContext;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "item")
@IdClass(ItemId.class)
public class Item {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Id
    @Column(name = "bpp_id", nullable = false)
    private String bppId;

    @Column(name = "bpp_uri")
    private String bppUri;

    @Column(name = "name")
    private String name;

    @Column(name = "context_url")
    private String contextUrl;

    @Column(name = "type")
    private String type;

    @Column(name = "provider_id")
    private String providerId;

    @Column(name = "catalog_id")
    private String catalogId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "network_id", columnDefinition = "TEXT[]")
    private String[] networkIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private String payload;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "offer_ids", columnDefinition = "text[]")
    private String[] offerIds;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected Item() {
    }

    public static Item from(String id, String payload, String[] offerIds, CatalogContext ctx,
            String catalogId, String name, String type, String providerId, String contextUrl) {
        Item item = new Item();
        item.id = id;
        item.bppId = ctx.bppId();
        item.bppUri = ctx.bppUri();
        item.name = name;
        item.contextUrl = contextUrl;
        item.type = type;
        item.providerId = providerId;
        item.catalogId = catalogId;
        item.networkIds = ctx.networkIds() != null ? ctx.networkIds() : new String[0];
        item.payload = payload;
        item.offerIds = offerIds != null ? offerIds : new String[0];
        return item;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof Item other))
            return false;
        return Objects.equals(id, other.id) && Objects.equals(bppId, other.bppId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, bppId);
    }

    public String getId() {
        return id;
    }

    public String getBppId() {
        return bppId;
    }

    public String getBppUri() {
        return bppUri;
    }

    public String getName() {
        return name;
    }

    public String getContextUrl() {
        return contextUrl;
    }

    public String getType() {
        return type;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getCatalogId() {
        return catalogId;
    }

    /** Returns a defensive copy; callers must not mutate the returned array. */
    public String[] getNetworkIds() {
        return networkIds != null ? networkIds.clone() : new String[0];
    }

    public String getPayload() {
        return payload;
    }

    /** Returns a defensive copy; callers must not mutate the returned array. */
    public String[] getOfferIds() {
        return offerIds != null ? offerIds.clone() : new String[0];
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
