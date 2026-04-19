package org.beckn.catalogpublish.store.jpa;

import org.beckn.catalogpublish.model.ProviderOffer;
import org.beckn.catalogpublish.model.ProviderOfferId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderOfferJpaRepository extends JpaRepository<ProviderOffer, ProviderOfferId> {

    @Modifying
    @Query("DELETE FROM ProviderOffer po WHERE po.catalogId = :catalogId")
    int deleteByCatalogId(@Param("catalogId") String catalogId);
}
