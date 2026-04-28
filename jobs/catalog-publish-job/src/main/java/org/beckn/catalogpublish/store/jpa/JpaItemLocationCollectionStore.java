package org.beckn.catalogpublish.store.jpa;

import org.beckn.catalogpublish.model.ItemLocationCollection;
import org.beckn.catalogpublish.store.ItemLocationCollectionStore;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
@Primary
public class JpaItemLocationCollectionStore implements ItemLocationCollectionStore {

    private final ItemLocationCollectionJpaRepository repo;

    public JpaItemLocationCollectionStore(ItemLocationCollectionJpaRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveLocations(List<ItemLocationCollection> locations) {
        if (locations != null && !locations.isEmpty()) {
            repo.saveAll(locations);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteByCatalogId(String catalogId) {
        return repo.deleteByCatalogId(catalogId);
    }
}
