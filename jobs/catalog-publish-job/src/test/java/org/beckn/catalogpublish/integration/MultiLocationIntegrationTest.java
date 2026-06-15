package org.beckn.catalogpublish.integration;

import org.beckn.catalogpublish.orchestration.CatalogPublishOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for #306: a resource/provider may publish MULTIPLE geometries under one
 * wildcard path (e.g. {@code availableAt[*].geo}). Before the fix, the PK
 * (item_id, catalog_id, path) collapsed them to a single row (last write wins); the per-path
 * {@code seq} ordinal now makes each geometry its own row.
 *
 * <p>These tests assert the actual persisted rows in {@code item_location_collection} — the
 * store that uniquely fans one resource out to many rows — across the publish/re-publish
 * lifecycle (no collapse, MERGE reduction removes stale rows, MERGE addition adds rows).</p>
 */
class MultiLocationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    CatalogPublishOrchestrator orchestrator;

    private long locationCount(String catalogId, String itemId) {
        return locationRepository.findAll().stream()
                .filter(l -> catalogId.equals(l.getId().getCatalogId())
                        && itemId.equals(l.getId().getItemId()))
                .count();
    }

    private List<Short> seqs(String catalogId, String itemId) {
        return locationRepository.findAll().stream()
                .filter(l -> catalogId.equals(l.getId().getCatalogId())
                        && itemId.equals(l.getId().getItemId()))
                .map(l -> l.getId().getSeq())
                .sorted()
                .toList();
    }

    private static String publish(String messageId, String updateMode, String geoArray) {
        return publish(messageId, updateMode, geoArray, "ML Resource");
    }

    private static String publish(String messageId, String updateMode, String geoArray, String name) {
        String directives = updateMode == null ? ""
                : "\"publishDirectives\":[{\"catalogId\":\"cat-ml\",\"catalogType\":\"regular\",\"updateMode\":\"" + updateMode + "\"}],";
        return """
                {
                  "context": {"bppId":"bpp-1","bppUri":"http://bpp1.example.com",
                               "messageId":"%s","transactionId":"%s"},
                  "message": {
                    %s
                    "catalogs": [{
                      "id": "cat-ml",
                      "resources": [{
                        "id": "res-ml",
                        "descriptor": {"name": "%s"},
                        "availableAt": %s
                      }],
                      "offers": []
                    }]
                  }
                }""".formatted(messageId, messageId, directives, name, geoArray);
    }

    private static final String DELHI  = "{\"geo\":{\"type\":\"Point\",\"coordinates\":[77.10,28.70]}}";
    private static final String MUMBAI = "{\"geo\":{\"type\":\"Point\",\"coordinates\":[72.87,19.07]}}";
    private static final String CHENNAI = "{\"geo\":{\"type\":\"Point\",\"coordinates\":[80.27,13.08]}}";

    @Test
    void multipleLocations_persistedAsDistinctRows_notCollapsed() {
        orchestrator.processPublish(publish("m1", null, "[" + DELHI + "," + MUMBAI + "]"));

        // #306 core: two geometries under the same wildcard path => two rows, not one.
        assertThat(locationCount("cat-ml", "res-ml")).isEqualTo(2);
        assertThat(seqs("cat-ml", "res-ml")).containsExactly((short) 0, (short) 1);
    }

    @Test
    void mergeReducingLocations_removesStaleRow() {
        // Round 1: two locations.
        orchestrator.processPublish(publish("m1", null, "[" + DELHI + "," + MUMBAI + "]"));
        assertThat(locationCount("cat-ml", "res-ml")).isEqualTo(2);

        // Round 2 (MERGE, default): provider closed Mumbai -> only Delhi remains.
        orchestrator.processPublish(publish("m2", null, "[" + DELHI + "]"));

        // The stale Mumbai (seq=1) row must be gone — exactly one row left.
        assertThat(locationCount("cat-ml", "res-ml")).isEqualTo(1);
        assertThat(seqs("cat-ml", "res-ml")).containsExactly((short) 0);
    }

    @Test
    void mergeAddingLocations_addsNewRow() {
        // Round 1: one location.
        orchestrator.processPublish(publish("m1", null, "[" + DELHI + "]"));
        assertThat(locationCount("cat-ml", "res-ml")).isEqualTo(1);

        // Round 2 (MERGE): provider opens two more -> three total.
        orchestrator.processPublish(publish("m2", null, "[" + DELHI + "," + MUMBAI + "," + CHENNAI + "]"));

        assertThat(locationCount("cat-ml", "res-ml")).isEqualTo(3);
        assertThat(seqs("cat-ml", "res-ml")).containsExactly((short) 0, (short) 1, (short) 2);
    }

    @Test
    void mergeRepublish_itemRowSurvivesAndPayloadUpdated_noDataLoss() {
        // Round 1 (MERGE): item "Old Name" with two locations.
        orchestrator.processPublish(publish("m1", null, "[" + DELHI + "," + MUMBAI + "]", "Old Name"));

        // Round 2 (MERGE): the per-item location delete runs with clearAutomatically=true AFTER
        // the item upsert is queued. flushAutomatically=true must flush that upsert to the DB
        // before the context is cleared — otherwise the item row's update is silently discarded
        // (data loss). Change BOTH the descriptor (independent of geo handling) and the location set.
        orchestrator.processPublish(publish("m2", null, "[" + DELHI + "]", "New Name"));

        // The item row must exist exactly once and reflect ROUND 2 — proving the upsert was
        // persisted, not dropped by the context clear.
        var items = itemRepository.findAllByIdInAndCatalogId(List.of("res-ml"), "cat-ml");
        assertThat(items).hasSize(1);
        String payload = items.get(0).getPayload();
        assertThat(payload).contains("New Name");        // round-2 descriptor persisted
        assertThat(payload).doesNotContain("Old Name");   // round-1 payload fully replaced
        assertThat(payload).contains("77.1");             // Delhi retained
        assertThat(payload).doesNotContain("72.87");       // Mumbai removed from payload

        // And the location rows match the reduced set (no stale Mumbai row).
        assertThat(locationCount("cat-ml", "res-ml")).isEqualTo(1);
        assertThat(seqs("cat-ml", "res-ml")).containsExactly((short) 0);
    }

    @Test
    void fullReplaceReducingLocations_removesStaleRow() {
        // Round 1 (MERGE default): two locations.
        orchestrator.processPublish(publish("m1", null, "[" + DELHI + "," + MUMBAI + "]"));
        assertThat(locationCount("cat-ml", "res-ml")).isEqualTo(2);

        // Round 2 (FULL): one location — whole-catalog delete then reinsert.
        orchestrator.processPublish(publish("m2", "FULL", "[" + DELHI + "]"));

        assertThat(locationCount("cat-ml", "res-ml")).isEqualTo(1);
        assertThat(seqs("cat-ml", "res-ml")).containsExactly((short) 0);
    }
}
