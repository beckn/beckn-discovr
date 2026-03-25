package org.beckn.seeker.integration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;

@SpringBootTest
@Testcontainers
@DirtiesContext
class SeekerNotifierIntegrationTest {

  private static final Logger logger = LoggerFactory.getLogger(SeekerNotifierIntegrationTest.class);

  @Container
  @SuppressWarnings("resource")
  static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
      .withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("KAFKA"));

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    logger.info("🚀 Starting TestContainers Kafka setup...");
    logger.info("📦 This may take 30-60 seconds to download and start Kafka container");
    logger.info("⏳ Please wait while TestContainers brings up the Kafka broker...");

    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("topics.input", () -> "test.seeker.requests");
    registry.add("topics.dlt", () -> "test.seeker.dlt");

    logger.info("✅ Kafka TestContainer configured with bootstrap servers: {}", kafka.getBootstrapServers());
  }

  @Autowired
  private KafkaTemplate<String, String> kafkaTemplate;

  @Autowired
  private RestTemplate restTemplate;

  private Consumer<String, String> testConsumer;
  private MockRestServiceServer mockServer;

  @BeforeEach
  void setUp() {
    logger.info("🔧 Setting up test consumer and mock HTTP server for integration test...");

    // Setup mock HTTP server for BAP endpoint calls
    mockServer = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();

    Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
        kafka.getBootstrapServers(), "test-consumer-group-" + System.currentTimeMillis(), "true");
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    testConsumer = new KafkaConsumer<>(consumerProps);

    logger.info("✅ Test consumer and mock server configured successfully");
  }

  @Test
  void shouldProcessMessageAndSendToBapEndpoint() {
    logger.info("🧪 Starting end-to-end message processing test...");

    // Given - Valid discovery response message with BAP URI (Beckn v2.0 camelCase context fields)
    String testMessage = """
        {
          "context": {
            "messageId": "msg-123",
            "bapUri": "https://bap.example.com/callback",
            "action": "on_discover"
          },
          "catalog": {
            "providers": []
          }
        }
        """;
    String inputTopic = "test.seeker.requests";
    String bapUrl = "https://bap.example.com/callback/on_discover";

    // Mock successful HTTP response from BAP endpoint (Beckn v2.0 ACK format)
    mockServer.expect(requestTo(bapUrl))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.context.messageId").value("msg-123"))
        .andRespond(withSuccess("{\"status\":\"ACK\"}", MediaType.APPLICATION_JSON));

    // When - Send message to input topic
    logger.info("📤 Sending test message to input topic: {}", inputTopic);
    kafkaTemplate.send(inputTopic, "test-key", testMessage);
    kafkaTemplate.flush();
    logger.info("✅ Message sent to input topic successfully");

    // Then - Verify HTTP call was made to BAP endpoint
    logger.info("⏳ Waiting for message to be processed and HTTP call to be made...");

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> {
          mockServer.verify();
        });

    logger.info("✅ End-to-end message processing test completed successfully!");
  }

  @Test
  void shouldProcessMessageAndSendToBppEndpoint() {
    logger.info("🧪 Starting BPP message processing test...");

    // Given - Valid discovery response message with BPP URI (Beckn v2.0 camelCase context fields)
    String testMessage = """
        {
          "context": {
            "messageId": "msg-456",
            "bppUri": "https://bpp.example.com",
            "action": "catalog/on_publish"
          },
          "catalog": {
            "providers": []
          }
        }
        """;
    String inputTopic = "test.seeker.requests";
    String bppUrl = "https://bpp.example.com/catalog/on_publish";

    // Mock successful HTTP response from BPP endpoint (Beckn v2.0 ACK format)
    mockServer.expect(requestTo(bppUrl))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.context.messageId").value("msg-456"))
        .andRespond(withSuccess("{\"status\":\"ACK\"}", MediaType.APPLICATION_JSON));

    // When - Send message to input topic
    logger.info("📤 Sending test message to input topic: {}", inputTopic);
    kafkaTemplate.send(inputTopic, "test-key-bpp", testMessage);
    kafkaTemplate.flush();
    logger.info("✅ Message sent to input topic successfully");

    // Then - Verify HTTP call was made to BPP endpoint
    logger.info("⏳ Waiting for message to be processed and HTTP call to be made...");

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> {
          mockServer.verify();
        });

    logger.info("✅ BPP message processing test completed successfully!");
  }

  @Test
  void shouldSendInvalidMessageToDlt() {
    logger.info("🧪 Starting DLT (Dead Letter Topic) test...");

    // Given
    String invalidMessage = "{ invalid json message";
    String inputTopic = "test.seeker.requests";
    String dltTopic = "test.seeker.dlt";

    // Subscribe to DLT topic
    testConsumer.subscribe(Collections.singletonList(dltTopic));
    logger.info("📡 Subscribed test consumer to DLT topic: {}", dltTopic);

    // When - Send invalid message to input topic
    logger.info("📤 Sending invalid JSON message to input topic: {}", inputTopic);
    kafkaTemplate.send(inputTopic, "error-key", invalidMessage);
    kafkaTemplate.flush();
    logger.info("✅ Invalid message sent to input topic");

    // Then - Verify message is sent to DLT
    logger.info("⏳ Waiting for invalid message to be processed and sent to DLT...");

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> {
          ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(2));
          logger.debug("🔍 Polling DLT for records, received {} records", records.count());

          assertThat(records.count()).isGreaterThan(0);

          ConsumerRecord<String, String> record = records.iterator().next();
          logger.info("📨 Received DLT message: key={}, value={}", record.key(), record.value());

          assertThat(record.key()).isEqualTo("error-key");
          assertThat(record.value()).contains("invalid json");

          // Verify error headers are present
          assertThat(record.headers().toArray()).isNotEmpty();
          logger.info("📋 DLT message contains error headers as expected");
        });

    logger.info("✅ DLT test completed successfully!");
  }

  @Test
  void shouldHandleMultipleMessagesCorrectly() {
    logger.info("🧪 Starting multiple messages processing test...");

    // Given
    String inputTopic = "test.seeker.requests";
    int messageCount = 3;
    String bapUrl = "https://bap.example.com/callback";
    String expectedUrl = bapUrl + "/on_discover";

    // Mock successful HTTP responses for all messages (Beckn v2.0 ACK format)
    for (int i = 0; i < messageCount; i++) {
      mockServer.expect(requestTo(expectedUrl))
          .andExpect(method(HttpMethod.POST))
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(jsonPath("$.context.messageId").value("msg-" + i))
          .andRespond(withSuccess("{\"status\":\"ACK\"}", MediaType.APPLICATION_JSON));
    }

    // When - Send multiple messages (Beckn v2.0 camelCase context fields)
    logger.info("📤 Sending {} messages to input topic...", messageCount);
    for (int i = 0; i < messageCount; i++) {
      String message = String.format("""
          {
            "context": {
              "messageId": "msg-%d",
              "bapUri": "%s",
              "action": "on_discover"
            },
            "catalog": {
              "providers": []
            }
          }
          """, i, bapUrl);
      kafkaTemplate.send(inputTopic, "key-" + i, message);
      logger.debug("📤 Sent message {}: {}", i, message);
    }
    kafkaTemplate.flush();
    logger.info("✅ All {} messages sent successfully", messageCount);

    // Then - Verify all HTTP calls were made
    logger.info("⏳ Waiting for all {} messages to be processed...", messageCount);

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> {
          mockServer.verify();
        });

    logger.info("✅ Multiple messages processing test completed successfully!");
  }

  @Test
  void contextLoads() {
    logger.info("🧪 Starting Spring context loading test...");
    logger.info("✅ Spring Boot context loaded successfully with TestContainers Kafka!");
    assertThat(kafka.isRunning()).isTrue();
    assertThat(kafkaTemplate).isNotNull();
    logger.info("✅ Context loading test completed!");
  }
}
