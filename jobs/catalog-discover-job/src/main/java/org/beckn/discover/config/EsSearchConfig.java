package org.beckn.discover.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
@ConditionalOnProperty(name = "discovery.text-search.engine", havingValue = "elasticsearch")
public class EsSearchConfig {

    @Bean
    public RestClient esRestClient(DiscoveryProperties props) {
        DiscoveryProperties.Elasticsearch es = props.getElasticsearch();
        HttpHost[] hosts = Arrays.stream(es.getHosts().split(","))
                .map(String::trim)
                .map(HttpHost::create)
                .toArray(HttpHost[]::new);
        return RestClient.builder(hosts)
                .setRequestConfigCallback(cfg -> cfg
                        .setConnectTimeout(es.getConnectTimeoutMs())
                        .setSocketTimeout(es.getSocketTimeoutMs()))
                .build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient esRestClient) {
        return new ElasticsearchClient(new RestClientTransport(esRestClient, new JacksonJsonpMapper()));
    }
}
