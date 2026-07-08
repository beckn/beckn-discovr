package org.beckn.seeker.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.beckn.seeker.logging.LogEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.HashMap;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.value;

@Slf4j
@Configuration
public class KafkaConsumerConfig {

    private final String bootstrapServers;
    private final String groupId;
    private final int listenerConcurrency;
    private final int maxPartitionFetchBytes;
    private final int fetchMaxBytes;

    public KafkaConsumerConfig(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id}") String groupId,
            @Value("${spring.kafka.listener.concurrency:1}") int listenerConcurrency,
            @Value("${spring.kafka.consumer.properties.max.partition.fetch.bytes:10485760}") int maxPartitionFetchBytes,
            @Value("${spring.kafka.consumer.properties.fetch.max.bytes:52428800}") int fetchMaxBytes) {
        this.bootstrapServers = bootstrapServers;
        this.groupId = groupId;
        this.listenerConcurrency = listenerConcurrency;
        this.maxPartitionFetchBytes = maxPartitionFetchBytes;
        this.fetchMaxBytes = fetchMaxBytes;
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // 10 MiB fetch ceiling — on_discover responses can carry multiple catalogs.
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, maxPartitionFetchBytes);
        props.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, fetchMaxBytes);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            CommonErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(listenerConcurrency);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        log.info("{}", value("event", "KAFKA_CONSUMER_CONFIGURED"),
                value("concurrency", listenerConcurrency));
        return factory;
    }

    @Bean
    public CommonErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                        @Value("${topics.dlt}") String dltTopic) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
            (consumerRecord, e) -> {
                log.error("{}", value("event", LogEvent.CONSUMER_ERROR),
                        value("reason", "sending to DLT"),
                        value("errorMessage", e.getMessage()));
                return new org.apache.kafka.common.TopicPartition(dltTopic, 0);
            });

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer);
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
            log.warn("{}", value("event", LogEvent.CONSUMER_ERROR),
                    value("deliveryAttempt", deliveryAttempt),
                    value("topic", record.topic()),
                    value("errorMessage", ex.getMessage()))
        );

        return errorHandler;
    }
}
