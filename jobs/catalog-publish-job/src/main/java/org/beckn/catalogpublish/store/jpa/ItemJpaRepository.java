package org.beckn.catalogpublish.store.jpa;

import org.beckn.catalogpublish.model.Item;
import org.beckn.catalogpublish.model.ItemId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ItemJpaRepository extends JpaRepository<Item, ItemId> {

    List<Item> findAllByIdInAndCatalogId(List<String> ids, String catalogId);

    @Query(value = """
        SELECT DISTINCT i.* FROM item i, unnest(i.offer_ids) AS oid
        WHERE i.catalog_id = :catalogId AND oid IN (:offerIds)
        """, nativeQuery = true)
    List<Item> findAllByCatalogIdAndAnyOfferId(
            @Param("catalogId") String catalogId,
            @Param("offerIds") List<String> offerIds);

    /** Cross-catalog lookup by resource ID only. Spring Data auto-derives the query. */
    List<Item> findAllByIdIn(List<String> ids);

    /** All items of a catalog. Used by Phase 3.5 to propagate a catalog-metadata change. */
    List<Item> findAllByCatalogId(String catalogId);

    /**
     * One arbitrary item of a catalog, used only as a sample to compare stored catalog metadata
     * against the incoming publish. LIMIT 1 on an indexed column — cheap.
     */
    Optional<Item> findFirstByCatalogId(String catalogId);

    /** Deletes all items for the given catalog. Used by FULL replace mode. Returns count of deleted rows. */
    @Modifying
    @Query("DELETE FROM Item i WHERE i.catalogId = :catalogId")
    int deleteByCatalogId(@Param("catalogId") String catalogId);
}
