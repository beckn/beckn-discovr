package org.beckn.catalogpublish.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "item")
@IdClass(ItemId.class)
public class Item {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Id
    @Column(name = "catalog_id", nullable = false)
    private String catalogId;

    @Column(name = "context_url")
    private String contextUrl;

    @Column(name = "type")
    private String type;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "network_id", columnDefinition = "TEXT[]")
    private String[] networkIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private String payload;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "offer_ids", columnDefinition = "text[]")
    private String[] offerIds;

    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @Column(name = "updated_by")
    private String updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected Item() {
    }

    public static Item from(String id, String payload, String[] offerIds,
            String subscriberId, String catalogId, String type, String contextUrl,
            String[] networkIds) {
        var item = new Item();
        item.id = id;
        item.catalogId = catalogId;
        item.contextUrl = contextUrl;
        item.type = type;
        item.networkIds = networkIds != null ? networkIds : new String[0];
        item.payload = payload;
        item.offerIds = offerIds != null ? offerIds : new String[0];
        item.createdBy = subscriberId;
        item.updatedBy = subscriberId;
        return item;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Item other)) return false;
        return Objects.equals(id, other.id) && Objects.equals(catalogId, other.catalogId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, catalogId);
    }

    public String getId() { return id; }

    public String getCatalogId() { return catalogId; }

    public String getContextUrl() { return contextUrl; }

    public String getType() { return type; }

    /** Returns an immutable view. */
    public List<String> getNetworkIds() {
        return networkIds != null ? List.of(networkIds) : List.of();
    }

    public String getPayload() { return payload; }

    /** Returns an immutable view. */
    public List<String> getOfferIds() {
        return offerIds != null ? List.of(offerIds) : List.of();
    }

    /** Raw array accessor for JPA / JdbcTypeCode — do not expose to callers. */
    String[] getOfferIdsArray() { return offerIds; }

    public String getCreatedBy() { return createdBy; }

    public String getUpdatedBy() { return updatedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
