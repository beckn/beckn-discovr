package org.beckn.catalogpublish.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ItemLocationId implements Serializable {

    @Column(name = "item_id")
    private String itemId;

    @Column(name = "catalog_id")
    private String catalogId;

    @Column(name = "path")
    private String path;

    // #306: per-path ordinal. A provider may publish multiple geometries under one
    // wildcard path (e.g. availableAt[*].geo); seq makes each its own row so they
    // no longer collide on the (item_id, catalog_id, path) key.
    @Column(name = "seq")
    private short seq;
}
