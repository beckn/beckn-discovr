package org.beckn.catalogpublish.store.jpa;

import org.beckn.catalogpublish.model.ItemLocationCollection;
import org.beckn.catalogpublish.model.ItemLocationId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemLocationCollectionJpaRepository
        extends JpaRepository<ItemLocationCollection, ItemLocationId> {

    /**
     * Deletes all location rows that belong to the given catalog.
     * The {@code catalog_id} column on {@code item_location_collection} makes this
     * a direct, catalog-scoped DELETE — no JOIN required, no cross-catalog contamination.
     */
    @Modifying
    @Query(value = "DELETE FROM item_location_collection WHERE catalog_id = :catalogId",
            nativeQuery = true)
    int deleteByCatalogId(@Param("catalogId") String catalogId);
}
