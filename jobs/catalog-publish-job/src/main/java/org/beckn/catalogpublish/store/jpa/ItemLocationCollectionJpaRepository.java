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
     * Deletes all location rows whose item belongs to the given catalog and BPP.
     * Uses a subquery because item_location_collection has no direct catalog_id/bpp_id columns.
     */
    @Modifying
    @Query(value = """
        DELETE FROM item_location_collection
        WHERE item_id IN (
            SELECT id FROM item WHERE catalog_id = :catalogId AND bpp_id = :bppId
        )
        """, nativeQuery = true)
    void deleteByCatalogIdAndBppId(@Param("catalogId") String catalogId, @Param("bppId") String bppId);
}
