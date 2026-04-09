package org.beckn.catalogpublish.store.jpa;

import org.beckn.catalogpublish.model.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ItemJpaRepository extends JpaRepository<Item, String> {

    List<Item> findAllByIdIn(List<String> ids);

    @Query(value = """
        SELECT DISTINCT i.* FROM item i, unnest(i.offer_ids) AS oid
        WHERE oid IN (:offerIds)
        """, nativeQuery = true)
    List<Item> findAllByAnyOfferId(@Param("offerIds") List<String> offerIds);
}
