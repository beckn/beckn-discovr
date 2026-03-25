package org.beckn.seeker.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {
    @Value("${topics.input}")
    private String inputTopic;
    
    @Value("${topics.dlt}")
    private String dltTopic;
    
    @Value("${kafka.topics.partitions:3}")
    private int numPartitions;
    
    @Value("${kafka.topics.replication-factor:1}")
    private short replicationFactor;

    @Bean
    public NewTopic inputTopic() {
        return TopicBuilder.name(inputTopic)
                .partitions(numPartitions)
                .replicas(replicationFactor)
                .build();
    }


    @Bean
    public NewTopic dltTopic() {
        return TopicBuilder.name(dltTopic)
                .partitions(numPartitions)
                .replicas(replicationFactor)
                .build();
    }
}
