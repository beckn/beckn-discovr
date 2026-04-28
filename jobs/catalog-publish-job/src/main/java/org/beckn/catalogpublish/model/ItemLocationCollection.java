package org.beckn.catalogpublish.model;

import jakarta.persistence.*;
import org.locationtech.jts.geom.Geometry;

@Entity
@Table(name = "item_location_collection")
public class ItemLocationCollection {

    @EmbeddedId
    private ItemLocationId id;

    @Column(name = "geom", columnDefinition = "GEOMETRY(Geometry, 4326)", nullable = false)
    private Geometry geom;

    protected ItemLocationCollection() {}

    public ItemLocationCollection(String itemId, String catalogId, String path, Geometry geom) {
        this.id = new ItemLocationId(itemId, catalogId, path);
        this.geom = geom;
    }

    public ItemLocationId getId() { return id; }
    public Geometry getGeom() { return geom; }
}
