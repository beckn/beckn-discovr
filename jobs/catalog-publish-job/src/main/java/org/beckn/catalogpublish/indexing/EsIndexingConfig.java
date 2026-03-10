package org.beckn.catalogpublish.indexing;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.beckn.catalogpublish.config.AppProperties;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.Arrays;

@Configuration
@ConditionalOnProperty(name = "app.catalog.elasticsearch.enabled", havingValue = "true")
public class EsIndexingConfig {

    @Bean
    public RestClient esRestClient(AppProperties props) {
        String hosts = props.catalog().elasticsearch().hosts();
        if (hosts == null || hosts.isBlank())
            throw new IllegalStateException(
                "app.catalog.elasticsearch.hosts must be set when elasticsearch.enabled=true");
        HttpHost[] httpHosts = Arrays.stream(hosts.split(","))
                .map(String::trim)
                .map(HttpHost::create)
                .toArray(HttpHost[]::new);
        return RestClient.builder(httpHosts)
                .setRequestConfigCallback(cfg -> cfg.setConnectTimeout(5_000).setSocketTimeout(30_000))
                .build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient esRestClient) {
        return new ElasticsearchClient(new RestClientTransport(esRestClient, new JacksonJsonpMapper()));
    }

    @Bean("esRecoveryListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> esRecoveryListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(1);
        // RECORD mode: auto-commit after each successful listener invocation.
        // EsFailureConsumer never throws — it handles all outcomes internally.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
