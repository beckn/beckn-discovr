package org.beckn.catalogpublish.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.beckn.catalogpublish.util.TagsProducerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka producer factory and template from AppProperties.
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, String> producerFactory(AppProperties props) {
        Map<String, Object> map = new HashMap<>();
        map.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.messaging().brokerServers());
        map.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        map.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // Idempotent delivery: exactly-once within a single producer session.
        // Requires acks=all; retries > 0; max.in.flight <= 5 (all set below).
        map.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        map.put(ProducerConfig.ACKS_CONFIG, "all");

        // Throughput: batch up to 32 KB or wait 5 ms before flushing.
        map.put(ProducerConfig.BATCH_SIZE_CONFIG, 32_768);
        map.put(ProducerConfig.LINGER_MS_CONFIG, 5);

        // Compression reduces broker write and network load at negligible CPU cost.
        map.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        // Allow up to 5 in-flight requests (safe with idempotence enabled).
        map.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        // Bound total delivery time so slow brokers don't block the consumer thread
        // long enough to exceed max.poll.interval.ms and trigger group eviction.
        map.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
        map.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);

        // Forward tags MDC field as Kafka header on every outbound message.
        map.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, TagsProducerInterceptor.class.getName());

        return new DefaultKafkaProducerFactory<>(map);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
