package org.beckn.seeker.integration;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.beckn.auth.BecknAuth;
import org.beckn.auth.verification.RegistryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest(properties = "http.client.url-validation-enabled=false")
@Testcontainers
@DirtiesContext
class SeekerNotifierIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(SeekerNotifierIntegrationTest.class);

    private static final String TEST_SUBSCRIBER_ID = "bap.example.com";
    private static final String TEST_RECORD_ID = "key-001";
    private static final String BAP_BASE_URL = "https://bap.example.com/callback";
    private static final String BPP_BASE_URL = "https://bpp.example.com";

    @Container
    @SuppressWarnings("resource")
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
            .withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("KAFKA"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("topics.input", () -> "test.seeker.requests");
        registry.add("topics.dlt", () -> "test.seeker.dlt");
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private RestTemplate restTemplate;

    @MockBean
    private BecknAuth becknAuth;

    private Consumer<String, String> testConsumer;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        mockServer = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                kafka.getBootstrapServers(), "test-consumer-group-" + System.currentTimeMillis(), "true");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        consumerProps.put("metadata.max.age.ms", "5000");
        testConsumer = new KafkaConsumer<>(consumerProps);

        // Pre-create DLT topic so consumers can discover it immediately
        ensureTopicExists("test.seeker.dlt");

        // Registry resolves subscriber base URL for any subscriber/record pair
        when(becknAuth.getRegistryEntry(TEST_SUBSCRIBER_ID, TEST_RECORD_ID))
                .thenReturn(new RegistryEntry(null, BAP_BASE_URL));
    }

    @Test
    void shouldProcessMessageAndSendToBapEndpoint() {
        // Given — on_discover response in dispatcher envelope format; registry resolves BAP base URL.
        // catalog-discover-job wraps every response: { "meta": { identity }, "payload": { Beckn response } }
        String testMessage = """
                {
                  "meta": {
                    "subscriber_id": "%s",
                    "record_id": "%s"
                  },
                  "payload": {
                    "context": {
                      "messageId": "msg-123",
                      "action": "on_discover"
                    },
                    "catalog": {
                      "providers": []
                    }
                  }
                }
                """.formatted(TEST_SUBSCRIBER_ID, TEST_RECORD_ID);
        String inputTopic = "test.seeker.requests";
        String expectedUrl = BAP_BASE_URL + "/on_discover";

        // HttpService POSTs the unwrapped payload — assert on payload.context fields
        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.context.messageId").value("msg-123"))
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andRespond(withSuccess("{\"status\":\"ACK\"}", MediaType.APPLICATION_JSON));

        // When — send with identity headers (headers take priority over meta)
        sendWithIdentityHeaders(inputTopic, "test-key", testMessage);

        // Then
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(mockServer::verify);
    }

    @Test
    void shouldProcessMessageAndSendToBppEndpoint() {
        // Given — catalog/on_publish response in envelope format; registry resolves BPP base URL
        String bppSubscriberId = "bpp.example.com";
        String bppRecordId = "key-002";
        when(becknAuth.getRegistryEntry(bppSubscriberId, bppRecordId))
                .thenReturn(new RegistryEntry(null, BPP_BASE_URL));

        String testMessage = """
                {
                  "meta": {
                    "subscriber_id": "%s",
                    "record_id": "%s"
                  },
                  "payload": {
                    "context": {
                      "messageId": "msg-456",
                      "action": "catalog/on_publish"
                    },
                    "catalog": {
                      "providers": []
                    }
                  }
                }
                """.formatted(bppSubscriberId, bppRecordId);
        String inputTopic = "test.seeker.requests";
        String expectedUrl = BPP_BASE_URL + "/catalog/on_publish";

        // HttpService POSTs the unwrapped payload — assert on payload.context fields
        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.context.messageId").value("msg-456"))
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andRespond(withSuccess("{\"status\":\"ACK\"}", MediaType.APPLICATION_JSON));

        // When — send with BPP identity headers (headers take priority over meta)
        var record = new ProducerRecord<String, String>(inputTopic, "test-key-bpp", testMessage);
        record.headers().add(new RecordHeader("subscriber_id", bppSubscriberId.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("record_id", bppRecordId.getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(record);
        kafkaTemplate.flush();

        // Then
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(mockServer::verify);
    }

    @Test
    void shouldSendInvalidMessageToDlt() {
        // Given
        String invalidMessage = "{ invalid json message";
        String inputTopic = "test.seeker.requests";
        String dltTopic = "test.seeker.dlt";

        testConsumer.subscribe(Collections.singletonList(dltTopic));
        // Trigger partition assignment before producing the DLT-bound message
        testConsumer.poll(Duration.ofSeconds(5));

        // When — identity headers don't matter for invalid JSON (fails before URL resolution)
        kafkaTemplate.send(inputTopic, "error-key", invalidMessage);
        kafkaTemplate.flush();

        // Then — collect records across multiple polls
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(2));
                    assertThat(records.count()).isGreaterThan(0);

                    for (ConsumerRecord<String, String> record : records) {
                        if ("error-key".equals(record.key())) {
                            assertThat(record.value()).contains("invalid json");
                            assertThat(record.headers().toArray()).isNotEmpty();
                            return;
                        }
                    }
                    fail("DLT record with key 'error-key' not found");
                });
    }

    @Test
    void shouldHandleMultipleMessagesCorrectly() {
        // Given — multiple on_discover responses in envelope format
        String inputTopic = "test.seeker.requests";
        int messageCount = 3;
        String expectedUrl = BAP_BASE_URL + "/on_discover";

        for (int i = 0; i < messageCount; i++) {
            mockServer.expect(requestTo(expectedUrl))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.context.messageId").value("msg-" + i))
                    .andExpect(jsonPath("$.meta").doesNotExist())
                    .andRespond(withSuccess("{\"status\":\"ACK\"}", MediaType.APPLICATION_JSON));
        }

        // When — send multiple envelope messages with identity headers
        for (int i = 0; i < messageCount; i++) {
            String message = """
                    {
                      "meta": {
                        "subscriber_id": "%s",
                        "record_id": "%s"
                      },
                      "payload": {
                        "context": {
                          "messageId": "msg-%d",
                          "action": "on_discover"
                        },
                        "catalog": {
                          "providers": []
                        }
                      }
                    }
                    """.formatted(TEST_SUBSCRIBER_ID, TEST_RECORD_ID, i);
            sendWithIdentityHeaders(inputTopic, "key-" + i, message);
        }

        // Then
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(mockServer::verify);
    }

    @Test
    void shouldDeliverCallbackUsingJsonMetaFallbackWhenHeadersAbsent() {
        // Given — envelope message with identity in meta but NO Kafka headers.
        // The dispatcher must fall back to meta.subscriber_id / meta.record_id.
        String testMessage = """
                {
                  "meta": {
                    "subscriber_id": "%s",
                    "record_id": "%s"
                  },
                  "payload": {
                    "context": {
                      "messageId": "msg-meta-fallback",
                      "action": "on_discover"
                    },
                    "catalog": {
                      "providers": []
                    }
                  }
                }
                """.formatted(TEST_SUBSCRIBER_ID, TEST_RECORD_ID);
        String inputTopic = "test.seeker.requests";
        String expectedUrl = BAP_BASE_URL + "/on_discover";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.context.messageId").value("msg-meta-fallback"))
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andRespond(withSuccess("{\"status\":\"ACK\"}", MediaType.APPLICATION_JSON));

        // When — send WITHOUT Kafka identity headers; identity comes only from JSON meta
        kafkaTemplate.send(inputTopic, "meta-fallback-key", testMessage);
        kafkaTemplate.flush();

        // Then — delivery succeeds via meta fallback; message never reaches DLT
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(mockServer::verify);
    }

    @Test
    void shouldSendToDltWhenBothHeadersAndMetaIdentityAbsent() {
        // Given — message with neither Kafka headers nor meta identity; no context fallback
        String testMessage = """
                {
                  "payload": {
                    "context": {
                      "messageId": "msg-no-identity",
                      "action": "on_discover"
                    },
                    "catalog": {
                      "providers": []
                    }
                  }
                }
                """;
        String inputTopic = "test.seeker.requests";
        String dltTopic = "test.seeker.dlt";

        testConsumer.subscribe(Collections.singletonList(dltTopic));
        // Trigger partition assignment before producing the DLT-bound message
        testConsumer.poll(Duration.ofSeconds(5));

        // When — send WITHOUT identity headers and without meta.subscriber_id/record_id
        kafkaTemplate.send(inputTopic, "no-identity-key", testMessage);
        kafkaTemplate.flush();

        // Then — message should end up in DLT because identity cannot be resolved
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(2));
                    assertThat(records.count()).isGreaterThan(0);

                    for (ConsumerRecord<String, String> record : records) {
                        if ("no-identity-key".equals(record.key())) {
                            // Original message body preserved in DLT
                            assertThat(record.value()).contains("msg-no-identity");
                            // Error headers carry the exception class
                            var errorClassHeader = record.headers().lastHeader("x-error-class");
                            assertThat(errorClassHeader).isNotNull();
                            var errorClass = new String(errorClassHeader.value(), StandardCharsets.UTF_8);
                            assertThat(errorClass).contains("CallbackDeliveryException");
                            return;
                        }
                    }
                    fail("DLT record with key 'no-identity-key' not found");
                });
    }

    @Test
    void contextLoads() {
        assertThat(kafka.isRunning()).isTrue();
        assertThat(kafkaTemplate).isNotNull();
    }

    /** Pre-creates a Kafka topic so consumers can subscribe immediately. */
    private static void ensureTopicExists(String topic) {
        try (var admin = org.apache.kafka.clients.admin.Admin.create(
                Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(Collections.singletonList(
                    new org.apache.kafka.clients.admin.NewTopic(topic, 1, (short) 1)
            )).all().get();
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                throw new RuntimeException(e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /** Sends a Kafka message with subscriber_id and record_id headers. */
    private void sendWithIdentityHeaders(String topic, String key, String value) {
        var record = new ProducerRecord<String, String>(topic, key, value);
        record.headers().add(new RecordHeader("subscriber_id",
                TEST_SUBSCRIBER_ID.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("record_id",
                TEST_RECORD_ID.getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(record);
        kafkaTemplate.flush();
    }
}
