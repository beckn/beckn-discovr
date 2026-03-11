package org.beckn.catalogpublish.integration;

import org.beckn.catalogpublish.orchestration.CatalogPublishOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class PublishFlowIntegrationTest extends BaseIntegrationTest {

    @Autowired
    CatalogPublishOrchestrator orchestrator;

    @Test
    void processPublish_persistsItemsAndLocations() {
        String fixture = readFixture("fixtures/ev_charging_station_data.json");
        var results = orchestrator.processPublish(fixture).results();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).catalogId()).isEqualTo("cat-1");
        assertThat(itemRepository.count()).isEqualTo(1);
    }
}
