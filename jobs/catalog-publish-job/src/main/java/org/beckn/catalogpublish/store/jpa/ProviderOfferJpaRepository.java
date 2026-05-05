package org.beckn.catalogpublish.store.jpa;

import org.beckn.catalogpublish.model.ProviderOffer;
import org.beckn.catalogpublish.model.ProviderOfferId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderOfferJpaRepository extends JpaRepository<ProviderOffer, ProviderOfferId> {

    @Modifying
    @Query(value = """
            INSERT INTO provider_offer (offer_id, catalog_id, provider_id, payload, created_at, updated_at)
            VALUES (:offerId, :catalogId, :providerId, CAST(:payload AS jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (offer_id, catalog_id) DO UPDATE SET
                provider_id = EXCLUDED.provider_id,
                payload     = EXCLUDED.payload,
                updated_at  = CURRENT_TIMESTAMP
            """, nativeQuery = true)
    void upsert(@Param("offerId") String offerId,
                @Param("catalogId") String catalogId,
                @Param("providerId") String providerId,
                @Param("payload") String payload);

    @Modifying
    @Query("DELETE FROM ProviderOffer po WHERE po.catalogId = :catalogId")
    int deleteByCatalogId(@Param("catalogId") String catalogId);
}
