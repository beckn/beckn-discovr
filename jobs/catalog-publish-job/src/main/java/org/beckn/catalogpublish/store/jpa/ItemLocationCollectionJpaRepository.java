package org.beckn.catalogpublish.store.jpa;

import org.beckn.catalogpublish.model.ItemLocationCollection;
import org.beckn.catalogpublish.model.ItemLocationId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

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

    /**
     * Deletes location rows for the given items within a catalog. Used by MERGE mode to
     * fully re-derive each published item's locations: a provider may reduce its set of
     * {@code availableAt} geometries between publishes, so its prior rows (including higher
     * {@code seq} ordinals) must be cleared before the freshly extracted set is inserted —
     * otherwise stale locations would keep matching spatial queries (#306). Scoped to the
     * published item ids so resources not in a partial MERGE publish are untouched.
     */
    @Modifying
    @Query(value = "DELETE FROM item_location_collection WHERE catalog_id = :catalogId AND item_id IN (:itemIds)",
            nativeQuery = true)
    int deleteByItemIdsAndCatalogId(@Param("itemIds") List<String> itemIds,
            @Param("catalogId") String catalogId);
}
