package org.beckn.catalogpublish.store.jpa;

import org.beckn.catalogpublish.model.Item;
import org.beckn.catalogpublish.store.ItemStore;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
@Primary
public class JpaItemStore implements ItemStore {

    private final ItemJpaRepository repo;

    public JpaItemStore(ItemJpaRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Item> saveAll(List<Item> items) {
        return repo.saveAll(items);
    }

    @Override
    public List<Item> findAllByIdIn(List<String> itemIds) {
        return itemIds == null || itemIds.isEmpty() ? List.of() : repo.findAllByIdIn(itemIds);
    }

    @Override
    public List<Item> findAllByAnyOfferId(List<String> offerIds) {
        return offerIds == null || offerIds.isEmpty() ? List.of() : repo.findAllByAnyOfferId(offerIds);
    }
}
