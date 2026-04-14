package org.beckn.catalogpublish.integration;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.beckn.catalogpublish.store.jpa.ItemJpaRepository;
import org.beckn.catalogpublish.store.jpa.ItemLocationCollectionJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class BaseIntegrationTest {

    // ── Container strategy ────────────────────────────────────────────────────
    //
    // 1. Singleton pattern: static finals mean containers are started ONCE per JVM.
    //    All test classes in the same JVM share them, so Spring context is loaded once.
    //
    // 2. withReuse(true): when testcontainers.reuse.enable=true is set in
    //    ~/.testcontainers.properties, Testcontainers reuses an already-running
    //    container across JVM runs (i.e., across ./gradlew integrationTest invocations).
    //    This cuts the ~40 s Docker startup from every local dev run.
    //    CI/CD: omit testcontainers.reuse.enable or set it to false for clean containers.
    //
    //    To enable locally, add to ~/.testcontainers.properties:
    //        testcontainers.reuse.enable=true
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:15-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("catalog_db")
            .withUsername("catalog_user")
            .withPassword("catalog123")
            .withReuse(true);

    @SuppressWarnings("resource")
    protected static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
            .withReuse(true);

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("app.datasource.username", () -> "catalog_user");
        registry.add("app.datasource.password", () -> "catalog123");
        registry.add("app.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("app.messaging.broker-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    protected ItemJpaRepository itemRepository;

    @Autowired
    protected ItemLocationCollectionJpaRepository locationRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearDatabase() {
        locationRepository.deleteAll();
        itemRepository.deleteAll();
    }

    protected String readFixture(String relativePath) {
        try {
            return Files.readString(Path.of("src", "test", "resources", relativePath));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read fixture: " + relativePath, e);
        }
    }

    // ── Shared Kafka helpers ──────────────────────────────────────────────────

    protected KafkaProducer<String, String> createProducer() {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        ));
    }

    protected KafkaConsumer<String, String> createConsumer(String groupId) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        ));
    }

    /**
     * Polls {@code topic} until a record matching {@code valueMatcher} is found
     * or {@code timeoutMs} elapses. Returns {@code null} if nothing matched.
     * Use for both positive assertions (assert not null) and negative assertions
     * (assert null with a short timeout).
     *
     * <p>Uses manual partition assignment ({@code assign} + {@code seekToBeginning}) instead of
     * {@code subscribe} to skip group-coordinator rebalancing, which otherwise adds ~1-2 s of
     * latency before the first record can be consumed.
     */
    protected ConsumerRecord<String, String> consumeOneRecord(String topic, String groupId,
                                                              long timeoutMs,
                                                              Predicate<String> valueMatcher) {
        try (KafkaConsumer<String, String> consumer = createConsumer(groupId)) {
            List<PartitionInfo> partitions = consumer.partitionsFor(topic, Duration.ofSeconds(5));
            List<TopicPartition> topicPartitions = partitions.stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition()))
                    .toList();
            consumer.assign(topicPartitions);
            consumer.seekToBeginning(topicPartitions);
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(50));
                for (ConsumerRecord<String, String> record : records) {
                    if (valueMatcher.test(record.value())) return record;
                }
            }
        }
        return null;
    }
}
