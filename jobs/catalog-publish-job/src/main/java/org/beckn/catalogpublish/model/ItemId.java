package org.beckn.catalogpublish.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for the {@code item} table: (id, catalogId).
 */
public class ItemId implements Serializable {

    private String id;
    private String catalogId;

    public ItemId() {}

    public ItemId(String id, String catalogId) {
        this.id = id;
        this.catalogId = catalogId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemId other)) return false;
        return Objects.equals(id, other.id) && Objects.equals(catalogId, other.catalogId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, catalogId);
    }
}
