package org.beckn.catalogpublish.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.beckn.catalogpublish.exception.PayloadParseException;
import org.beckn.catalogpublish.exception.ValidationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, String> consumerFactory(AppProperties props) {
        Map<String, Object> map = new HashMap<>();
        AppProperties.Messaging m = props.messaging();
        map.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, m.brokerServers());
        map.put(ConsumerConfig.GROUP_ID_CONFIG, m.consumer().groupId());
        map.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        map.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        map.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        map.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        map.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, m.consumer().maxPollRecords());
        map.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, m.consumer().sessionTimeoutMs());
        map.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, m.consumer().maxPollIntervalMs());
        // 10 MiB fetch ceiling — the ingestion topic carries Catalg's fully assembled catalog.
        map.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, m.consumer().maxPartitionFetchBytes());
        map.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, m.consumer().fetchMaxBytes());
        return new DefaultKafkaConsumerFactory<>(map);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            AppProperties props,
            KafkaTemplate<String, String> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(props.messaging().consumer().concurrency());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(consumerErrorHandler(kafkaTemplate, props));
        return factory;
    }

    @Bean
    public DefaultErrorHandler consumerErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            AppProperties props) {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxElapsedTime(300_000);
        backOff.setMaxInterval(30_000);
        // Route exhausted-retry messages to the same configured failed topic used by
        // FailedMessagePublisher (parse/validation rejections), so operators only need
        // to monitor a single dead-letter channel: app.messaging.topics.failed.
        String failedTopic = props.messaging().topics().failed();
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(failedTopic, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(PayloadParseException.class, ValidationException.class);
        return handler;
    }
}
