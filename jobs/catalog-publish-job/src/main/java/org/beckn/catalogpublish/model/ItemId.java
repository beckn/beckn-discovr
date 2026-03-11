package org.beckn.catalogpublish.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for the {@code item} table.
 * Required by JPA {@code @IdClass} on {@link Item}: (id, bppId).
 */
public class ItemId implements Serializable {

    private String id;
    private String bppId;

    public ItemId() {}

    public ItemId(String id, String bppId) {
        this.id    = id;
        this.bppId = bppId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemId other)) return false;
        return Objects.equals(id, other.id) && Objects.equals(bppId, other.bppId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, bppId);
    }
}
