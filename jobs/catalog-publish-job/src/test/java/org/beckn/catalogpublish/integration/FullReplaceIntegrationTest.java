package org.beckn.catalogpublish.integration;

import org.beckn.catalogpublish.model.ItemId;
import org.beckn.catalogpublish.orchestration.CatalogPublishOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FULL replace mode (updateMode=FULL in publishDirectives).
 *
 * <p>FULL mode deletes all existing items for the catalog+bpp before inserting fresh ones.
 * Items from the previous publish that are not in the new payload are removed.
 */
class FullReplaceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    CatalogPublishOrchestrator orchestrator;

    /**
     * Happy path: FULL replace deletes old items and inserts only the new ones.
     * item-old exists after round-1; round-2 sends updateMode=FULL with only item-new.
     * After round-2, only item-new must exist — item-old must be gone.
     */
    @Test
    void fullReplace_deletesStaleItemsAndInsertsNewOnes() {
        // Round 1: MERGE publish — item-old and item-keep
        String round1 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m1","transactionId":"t1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [
                      {"id": "item-old",  "descriptor": {"name": "Old Item"}},
                      {"id": "item-keep", "descriptor": {"name": "Keep Item"}}
                    ],
                    "offers": []}]}
                }""";
        orchestrator.processPublish(round1);
        assertThat(itemRepository.count()).isEqualTo(2);
        assertThat(itemRepository.findById(new ItemId("item-old",  "bpp-1"))).isPresent();
        assertThat(itemRepository.findById(new ItemId("item-keep", "bpp-1"))).isPresent();

        // Round 2: FULL replace — only item-new in the payload
        String round2 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {"catalogs": [{
                    "id": "cat-1",
                    "publishDirectives": {"updateMode": "FULL"},
                    "resources": [
                      {"id": "item-new", "descriptor": {"name": "New Item"}}
                    ],
                    "offers": []
                  }]}
                }""";
        var results = orchestrator.processPublish(round2).results();
        assertThat(results).hasSize(1);

        // item-old and item-keep must be gone; only item-new must exist
        assertThat(itemRepository.count()).isEqualTo(1);
        assertThat(itemRepository.findById(new ItemId("item-old",  "bpp-1"))).isEmpty();
        assertThat(itemRepository.findById(new ItemId("item-keep", "bpp-1"))).isEmpty();

        var newItem = itemRepository.findById(new ItemId("item-new", "bpp-1")).orElseThrow();
        assertThat(newItem.getName()).isEqualTo("New Item");
        assertThat(newItem.getCatalogId()).isEqualTo("cat-1");
    }

    /**
     * FULL replace is catalog-scoped: items from a different catalog belonging to the
     * same BPP must NOT be deleted.
     */
    @Test
    void fullReplace_onlyDeletesItemsOfTargetCatalog() {
        // Round 1: two catalogs — cat-1 and cat-2
        String round1 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m1","transactionId":"t1"},
                  "message": {"catalogs": [
                    {"id": "cat-1",
                      "resources": [{"id": "cat1-item", "descriptor": {"name": "Cat1 Item"}}],
                      "offers": []},
                    {"id": "cat-2",
                      "resources": [{"id": "cat2-item", "descriptor": {"name": "Cat2 Item"}}],
                      "offers": []}
                  ]}
                }""";
        orchestrator.processPublish(round1);
        assertThat(itemRepository.count()).isEqualTo(2);

        // Round 2: FULL replace on cat-1 only — cat-2 item must survive
        String round2 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {"catalogs": [{
                    "id": "cat-1",
                    "publishDirectives": {"updateMode": "FULL"},
                    "resources": [
                      {"id": "cat1-item-new", "descriptor": {"name": "Cat1 New Item"}}
                    ],
                    "offers": []
                  }]}
                }""";
        orchestrator.processPublish(round2);

        // cat-1 items replaced; cat-2 item unaffected
        assertThat(itemRepository.count()).isEqualTo(2);
        assertThat(itemRepository.findById(new ItemId("cat1-item",     "bpp-1"))).isEmpty();
        assertThat(itemRepository.findById(new ItemId("cat1-item-new", "bpp-1"))).isPresent();
        assertThat(itemRepository.findById(new ItemId("cat2-item",     "bpp-1"))).isPresent();
    }

    /**
     * Idempotency: two consecutive FULL replaces with the same payload produce exactly one row.
     */
    @Test
    void fullReplace_idempotent_twoIdenticalReplaces() {
        String payload = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m1","transactionId":"t1"},
                  "message": {"catalogs": [{
                    "id": "cat-1",
                    "publishDirectives": {"updateMode": "FULL"},
                    "resources": [
                      {"id": "item-a", "descriptor": {"name": "Item A"}}
                    ],
                    "offers": []
                  }]}
                }""";
        orchestrator.processPublish(payload);
        orchestrator.processPublish(payload);

        assertThat(itemRepository.count()).isEqualTo(1);
        var item = itemRepository.findById(new ItemId("item-a", "bpp-1")).orElseThrow();
        assertThat(item.getName()).isEqualTo("Item A");
    }

    /**
     * Default mode (no publishDirectives) is MERGE — items from previous publishes are preserved
     * unless explicitly included in the new payload.
     */
    @Test
    void mergeMode_default_preservesExistingItems() {
        // Round 1: two items
        String round1 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m1","transactionId":"t1"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [
                      {"id": "item-1", "descriptor": {"name": "Item One"}},
                      {"id": "item-2", "descriptor": {"name": "Item Two"}}
                    ],
                    "offers": []}]}
                }""";
        orchestrator.processPublish(round1);
        assertThat(itemRepository.count()).isEqualTo(2);

        // Round 2: MERGE (default) — only item-1 in payload; item-2 must survive
        String round2 = """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"m2","transactionId":"t2"},
                  "message": {"catalogs": [{"id": "cat-1",
                    "resources": [
                      {"id": "item-1", "descriptor": {"name": "Item One Updated"}}
                    ],
                    "offers": []}]}
                }""";
        orchestrator.processPublish(round2);

        assertThat(itemRepository.count()).isEqualTo(2);
        var item1 = itemRepository.findById(new ItemId("item-1", "bpp-1")).orElseThrow();
        var item2 = itemRepository.findById(new ItemId("item-2", "bpp-1")).orElseThrow();
        assertThat(item1.getName()).isEqualTo("Item One Updated");
        assertThat(item2.getName()).isEqualTo("Item Two");
    }
}
