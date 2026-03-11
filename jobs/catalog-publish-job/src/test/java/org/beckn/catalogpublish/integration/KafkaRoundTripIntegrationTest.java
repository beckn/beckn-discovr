package org.beckn.catalogpublish.integration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class KafkaRoundTripIntegrationTest extends BaseIntegrationTest {

    @Test
    void publishMessage_throughKafka_persistsItemAndPublishesResponse() {
        String fixture = readFixture("fixtures/ev_charging_station_data.json");
        String ingestionTopic = "catalog.v2.upload.requests";
        String responseTopic = "catalog.responses";

        try (KafkaProducer<String, String> producer = createProducer()) {
            producer.send(new ProducerRecord<>(ingestionTopic, fixture));
            producer.flush();
        }

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(itemRepository.count()).isGreaterThan(0));

        ConsumerRecord<String, String> record = consumeOneRecord(
                responseTopic, "verify-response-group", 8_000,
                v -> v.contains("on_catalog_publish"));

        assertThat(record)
                .as("Expected a response message on %s containing 'on_catalog_publish'", responseTopic)
                .isNotNull();
    }

}
