package org.beckn.catalogpublish.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.model.Item;
import org.beckn.catalogpublish.model.ItemId;
import org.beckn.catalogpublish.store.jpa.ItemJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full integration tests with no mocks: real Kafka + DB.
 * Asserts DB state and Kafka response/item messages after publish and upsert flows.
 * Note: offer-propagation (updating items via offer-ID lookup without explicit item list)
 * is not supported in the unified publish flow — items must be explicitly listed in the payload.
 */
class CatalogPublishSolidIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AppProperties props;

    @Autowired
    private ItemJpaRepository itemRepository;

    @Autowired
    private ObjectMapper objectMapper;

    // --- Tests using ev_charging_catalog_example.json (3 items, 3 offers, one offer linked to 2 items) ---

    @Test
    void publishExampleCatalog_fullRoundTrip_assertsDbAndKafkaResponse() throws Exception {
        String payload = readFixture("fixtures/ev_charging_catalog_example.json");

        try (KafkaProducer<String, String> producer = createProducer()) {
            producer.send(new ProducerRecord<>(props.messaging().topics().ingestionRequests(), payload));
            producer.flush();
        }

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isEqualTo(3));

        List<Item> items = itemRepository.findAll();
        Set<String> ids = items.stream().map(Item::getId).collect(Collectors.toSet());
        assertThat(ids).containsExactlyInAnyOrder("ev-charger-ccs2-001", "ev-charger-ccs2-002", "ev-charger-type2-001");

        items.forEach(item -> assertThat(item.getCatalogId()).isEqualTo("catalog-ev-charging-001"));

        Item ccs2_001 = items.stream().filter(i -> "ev-charger-ccs2-001".equals(i.getId())).findFirst().orElseThrow();
        Item ccs2_002 = items.stream().filter(i -> "ev-charger-ccs2-002".equals(i.getId())).findFirst().orElseThrow();
        Item type2_001 = items.stream().filter(i -> "ev-charger-type2-001".equals(i.getId())).findFirst().orElseThrow();

        // Assert persisted columns: type, catalog_id, context_url (name/providerId removed from schema)
        assertThat(ccs2_001.getType()).isEqualTo("ChargingService");
        assertThat(ccs2_001.getCatalogId()).isEqualTo("catalog-ev-charging-001");
        assertThat(ccs2_001.getContextUrl()).isNotNull().contains("context.jsonld");
        assertThat(ccs2_001.getPayload()).contains("DC Fast Charger - CCS2 (60kW)");

        assertThat(ccs2_002.getType()).isEqualTo("ChargingService");
        assertThat(ccs2_002.getCatalogId()).isEqualTo("catalog-ev-charging-001");
        assertThat(ccs2_002.getContextUrl()).isNotNull();
        assertThat(ccs2_002.getPayload()).contains("DC Fast Charger - CCS2 (120kW)");

        assertThat(type2_001.getType()).isEqualTo("ChargingService");
        assertThat(type2_001.getCatalogId()).isEqualTo("catalog-ev-charging-001");
        assertThat(type2_001.getContextUrl()).isNotNull();
        assertThat(type2_001.getPayload()).contains("AC Fast Charger - Type 2 (22kW)");

        assertThat(ccs2_001.getOfferIds()).containsExactlyInAnyOrder("offer-ccs2-60kw-kwh", "offer-ccs2-120kw-kwh");
        assertThat(ccs2_002.getOfferIds()).containsExactlyInAnyOrder("offer-ccs2-120kw-kwh");
        assertThat(type2_001.getOfferIds()).containsExactlyInAnyOrder("offer-type2-22kw-kwh");

        assertThat(ccs2_001.getPayload()).contains("Per-kWh Tariff - CCS2 60kW").contains("Per-kWh Tariff - CCS2 120kW");
        assertThat(ccs2_002.getPayload()).contains("Per-kWh Tariff - CCS2 120kW");
        assertThat(type2_001.getPayload()).contains("Per-kWh Tariff - Type 2 22kW");

        String responseTopic = props.messaging().topics().responses();
        ConsumerRecord<String, String> responseRecord = consumeOneRecord(responseTopic,
                "publish-example-response-group", 8_000, v -> {
                    if (!v.contains("on_catalog_publish")) return false;
                    try {
                        JsonNode r = objectMapper.readTree(v);
                        JsonNode res = r.path("message").path("results");
                        return res.size() == 1
                                && "catalog-ev-charging-001".equals(res.get(0).path("catalogId").asText())
                                && res.get(0).path("itemCount").asInt() == 3;
                    } catch (Exception e) { return false; }
                });
        assertThat(responseRecord).as("Expected on_catalog_publish response on %s", responseTopic).isNotNull();

        JsonNode response = objectMapper.readTree(responseRecord.value());
        assertThat(response.path("context").path("action").asText()).isEqualTo("on_catalog_publish");
        JsonNode results = response.path("message").path("results");
        assertThat(results.isArray()).isTrue();
        assertThat(results.size()).isEqualTo(1);
        assertThat(results.get(0).path("catalogId").asText()).isEqualTo("catalog-ev-charging-001");
        assertThat(results.get(0).path("itemCount").asInt()).isEqualTo(3);
    }

    @Test
    void upsertExample_itemAndOfferUpdates_assertsDbAndResponse() throws Exception {
        String publishPayload = readFixture("fixtures/ev_charging_catalog_example.json");
        String upsertPayload = readFixture("fixtures/ev_charging_patch_example.json");

        try (KafkaProducer<String, String> producer = createProducer()) {
            producer.send(new ProducerRecord<>(props.messaging().topics().ingestionRequests(), publishPayload));
            producer.flush();
        }

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isEqualTo(3));

        Item ccs2_001Before = itemRepository.findById(new ItemId("ev-charger-ccs2-001", "catalog-ev-charging-001")).orElseThrow();
        assertThat(ccs2_001Before.getPayload()).contains("isActive");
        assertThat(ccs2_001Before.getPayload()).contains("77.5946");
        assertThat(ccs2_001Before.getPayload()).contains("value").contains("18.0");

        try (KafkaProducer<String, String> producer = createProducer()) {
            producer.send(new ProducerRecord<>(props.messaging().topics().ingestionRequests(), upsertPayload));
            producer.flush();
        }

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Item item = itemRepository.findById(new ItemId("ev-charger-ccs2-001", "catalog-ev-charging-001")).orElseThrow();
                    assertThat(item.getPayload()).contains("isActive");
                    assertThat(item.getPayload()).contains("99.5946");
                    assertThat(item.getPayload()).contains("value").contains("22.1");
                });

        Item ccs2_001After = itemRepository.findById(new ItemId("ev-charger-ccs2-001", "catalog-ev-charging-001")).orElseThrow();
        assertThat(ccs2_001After.getPayload()).contains("isActive").contains("true");
        assertThat(ccs2_001After.getPayload()).contains("99.5946");
        assertThat(ccs2_001After.getPayload()).contains("value").contains("22.1");
        assertThat(ccs2_001After.getPayload()).contains("unitQuantity").contains("1.1");
        assertThat(ccs2_001After.getPayload()).contains("EcoPower Charging Pvt Ltd");
        // On upsert, persisted columns remain/update from merged payload
        assertThat(ccs2_001After.getType()).isEqualTo("ChargingService");
        assertThat(ccs2_001After.getCatalogId()).isEqualTo("catalog-ev-charging-001");
        assertThat(ccs2_001After.getPayload()).contains("DC Fast Charger - CCS2 (60kW)");

        String responseTopic = props.messaging().topics().responses();
        ConsumerRecord<String, String> responseRecord = consumeOneRecord(responseTopic,
                "upsert-example-item-offer-group", 8_000, v -> {
                    if (!v.contains("on_catalog_publish")) return false;
                    try {
                        JsonNode r = objectMapper.readTree(v);
                        JsonNode res = r.path("message").path("results");
                        return res.size() == 1 && res.get(0).path("itemCount").asInt() == 1
                                && "catalog-ev-charging-001".equals(res.get(0).path("catalogId").asText());
                    } catch (Exception e) { return false; }
                });
        assertThat(responseRecord).as("Expected on_catalog_publish response on %s", responseTopic).isNotNull();
        JsonNode response = objectMapper.readTree(responseRecord.value());
        JsonNode results = response.path("message").path("results");
        assertThat(results.size()).isEqualTo(1);
        assertThat(results.get(0).path("catalogId").asText()).isEqualTo("catalog-ev-charging-001");
        assertThat(results.get(0).path("itemCount").asInt()).isEqualTo(1);
    }

    // --- Multi-catalog: array of catalogs → all catalogs, items, and geo updated; solid assertions ---

    @Test
    void publishMultiCatalog_fullRoundTrip_assertsDbAndKafkaResponse() throws Exception {
        String payload = readFixture("fixtures/multi_catalog_publish.json");

        try (KafkaProducer<String, String> producer = createProducer()) {
            producer.send(new ProducerRecord<>(props.messaging().topics().ingestionRequests(), payload));
            producer.flush();
        }

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isEqualTo(5));

        List<Item> items = itemRepository.findAll();
        Set<String> ids = items.stream().map(Item::getId).collect(Collectors.toSet());
        assertThat(ids).containsExactlyInAnyOrder("item-1", "item-2", "item-3", "item-4", "item-5");

        // Items belong to cat-1 or cat-2 based on their source catalog
        items.forEach(item -> assertThat(item.getCatalogId()).isIn("cat-1", "cat-2"));

        Item item1 = items.stream().filter(i -> "item-1".equals(i.getId())).findFirst().orElseThrow();
        Item item2 = items.stream().filter(i -> "item-2".equals(i.getId())).findFirst().orElseThrow();
        Item item3 = items.stream().filter(i -> "item-3".equals(i.getId())).findFirst().orElseThrow();
        Item item4 = items.stream().filter(i -> "item-4".equals(i.getId())).findFirst().orElseThrow();
        Item item5 = items.stream().filter(i -> "item-5".equals(i.getId())).findFirst().orElseThrow();

        assertThat(item1.getOfferIds()).containsExactlyInAnyOrder("offer-A");
        assertThat(item2.getOfferIds()).containsExactlyInAnyOrder("offer-A");
        assertThat(item3.getOfferIds()).containsExactlyInAnyOrder("offer-B");
        assertThat(item4.getOfferIds()).containsExactlyInAnyOrder("offer-C");
        assertThat(item5.getOfferIds()).containsExactlyInAnyOrder("offer-C");

        assertThat(item1.getPayload()).contains("Offer A Original");
        assertThat(item2.getPayload()).contains("Offer A Original");
        assertThat(item3.getPayload()).contains("Offer B");
        assertThat(item4.getPayload()).contains("Offer C");
        assertThat(item5.getPayload()).contains("Offer C");

        String responseTopic = props.messaging().topics().responses();
        ConsumerRecord<String, String> responseRecord = consumeOneRecord(responseTopic,
                "publish-response-group", 8_000, v -> {
                    if (!v.contains("on_catalog_publish")) return false;
                    try {
                        JsonNode r = objectMapper.readTree(v);
                        JsonNode res = r.path("message").path("results");
                        if (res.size() != 2) return false;
                        Set<String> catalogIds = Set.of(
                                res.get(0).path("catalogId").asText(),
                                res.get(1).path("catalogId").asText());
                        return catalogIds.contains("cat-1") && catalogIds.contains("cat-2");
                    } catch (Exception e) { return false; }
                });
        assertThat(responseRecord).as("Expected on_catalog_publish response on %s", responseTopic).isNotNull();

        JsonNode response = objectMapper.readTree(responseRecord.value());
        assertThat(response.path("context").path("action").asText()).isEqualTo("on_catalog_publish");
        JsonNode results = response.path("message").path("results");
        assertThat(results.isArray()).isTrue();
        assertThat(results.size()).as("When array of catalogs is passed, all catalogs must be in response").isEqualTo(2);

        JsonNode r0 = results.get(0);
        JsonNode r1 = results.get(1);
        assertThat(r0.path("catalogId").asText()).isIn("cat-1", "cat-2");
        assertThat(r1.path("catalogId").asText()).isIn("cat-1", "cat-2");
        assertThat(Set.of(r0.path("catalogId").asText(), r1.path("catalogId").asText()))
                .containsExactlyInAnyOrder("cat-1", "cat-2");

        JsonNode cat1Result = r0.path("catalogId").asText().equals("cat-1") ? r0 : r1;
        JsonNode cat2Result = r0.path("catalogId").asText().equals("cat-2") ? r0 : r1;
        assertThat(cat1Result.path("itemCount").asInt()).as("cat-1 must have 3 items").isEqualTo(3);
        assertThat(cat2Result.path("itemCount").asInt()).as("cat-2 must have 2 items").isEqualTo(2);
    }

    @Test
    void upsertFlow_fullRoundTrip_assertsDbAndResponse() throws Exception {
        String publishPayload = readFixture("fixtures/ev_charging_station_data.json");
        String upsertPayload = readFixture("fixtures/ev_charging_patch_update.json");

        try (KafkaProducer<String, String> producer = createProducer()) {
            producer.send(new ProducerRecord<>(props.messaging().topics().ingestionRequests(), publishPayload));
            producer.flush();
        }

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isEqualTo(1));

        Item before = itemRepository.findAll().get(0);
        assertThat(before.getPayload()).contains("EV Station").doesNotContain("EV Station Updated");

        try (KafkaProducer<String, String> producer = createProducer()) {
            producer.send(new ProducerRecord<>(props.messaging().topics().ingestionRequests(), upsertPayload));
            producer.flush();
        }

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Item item = itemRepository.findById(new ItemId(before.getId(), before.getCatalogId())).orElseThrow();
                    assertThat(item.getPayload()).contains("EV Station Updated");
                });

        Item after = itemRepository.findAll().get(0);
        assertThat(after.getPayload()).contains("EV Station Updated");

        String responseTopic = props.messaging().topics().responses();
        ConsumerRecord<String, String> responseRecord = consumeOneRecord(responseTopic,
                "upsert-single-response-group", 8_000, v -> {
                    if (!v.contains("on_catalog_publish")) return false;
                    try {
                        JsonNode r = objectMapper.readTree(v);
                        JsonNode res = r.path("message").path("results");
                        return res.size() == 1 && "cat-1".equals(res.get(0).path("catalogId").asText())
                                && res.get(0).path("itemCount").asInt() == 1;
                    } catch (Exception e) { return false; }
                });
        assertThat(responseRecord).as("Expected on_catalog_publish response on %s", responseTopic).isNotNull();
        JsonNode response = objectMapper.readTree(responseRecord.value());
        JsonNode results = response.path("message").path("results");
        assertThat(results.size()).isEqualTo(1);
        assertThat(results.get(0).path("catalogId").asText()).isEqualTo("cat-1");
        assertThat(results.get(0).path("itemCount").asInt()).isEqualTo(1);
    }
}
