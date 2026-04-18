package org.beckn.catalogpublish;

import org.beckn.catalogpublish.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })
@EnableKafka
@EnableRetry
@EnableConfigurationProperties(AppProperties.class)
public class CatalogPublishApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogPublishApplication.class, args);
    }
}
