package org.beckn.catalogpublish.store.jpa;

import org.beckn.catalogpublish.model.Item;
import org.beckn.catalogpublish.store.ItemStore;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Repository
@Primary
public class JpaItemStore implements ItemStore {

    private static final int QUERY_CHUNK_SIZE = 500;

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
    public List<Item> findAllByIdInAndBppId(List<String> itemIds, String bppId) {
        return itemIds.isEmpty() ? List.of() : repo.findAllByIdInAndBppId(itemIds, bppId);
    }

    @Override
    public List<Item> findAllByBppIdAndAnyOfferId(String bppId, List<String> offerIds) {
        return offerIds == null || offerIds.isEmpty()
                ? List.of()
                : repo.findAllByBppIdAndAnyOfferId(bppId, offerIds);
    }

    @Override
    public List<Item> findAllByIdIn(List<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) return List.of();
        // Chunk into batches to avoid PostgreSQL bind parameter limits at scale
        if (itemIds.size() <= QUERY_CHUNK_SIZE) return repo.findAllByIdIn(itemIds);
        var results = new ArrayList<Item>();
        for (int i = 0; i < itemIds.size(); i += QUERY_CHUNK_SIZE) {
            var chunk = itemIds.subList(i, Math.min(i + QUERY_CHUNK_SIZE, itemIds.size()));
            results.addAll(repo.findAllByIdIn(chunk));
        }
        return results;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteByCatalogIdAndBppId(String catalogId, String bppId) {
        return repo.deleteByCatalogIdAndBppId(catalogId, bppId);
    }
}
