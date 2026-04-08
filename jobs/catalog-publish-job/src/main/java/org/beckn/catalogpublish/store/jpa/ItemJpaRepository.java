package org.beckn.catalogpublish.store.jpa;

import org.beckn.catalogpublish.model.Item;
import org.beckn.catalogpublish.model.ItemId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ItemJpaRepository extends JpaRepository<Item, ItemId> {

    List<Item> findAllByIdInAndBppId(List<String> ids, String bppId);

    @Query(value = """
        SELECT DISTINCT i.* FROM item i, unnest(i.offer_ids) AS oid
        WHERE i.bpp_id = :bppId AND oid IN (:offerIds)
        """, nativeQuery = true)
    List<Item> findAllByBppIdAndAnyOfferId(
            @Param("bppId") String bppId,
            @Param("offerIds") List<String> offerIds);

    /** Cross-BPP lookup by resource ID only — no BPP filter. Spring Data auto-derives the query. */
    List<Item> findAllByIdIn(List<String> ids);

    /** Deletes all items for the given catalog and BPP. Used by FULL replace mode. Returns count of deleted rows. */
    @Modifying
    @Query("DELETE FROM Item i WHERE i.catalogId = :catalogId AND i.bppId = :bppId")
    int deleteByCatalogIdAndBppId(@Param("catalogId") String catalogId, @Param("bppId") String bppId);
}
