package org.beckn.seeker.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(HttpClientProperties.class)
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(HttpClientProperties httpClientProperties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(httpClientProperties.connectionTimeout());
        factory.setReadTimeout(httpClientProperties.timeout());
        return new RestTemplate(factory);
    }
}
