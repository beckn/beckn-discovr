package org.beckn.crawler;

import org.beckn.crawler.config.CrawlerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Decentralized Catalog Crawler (POC).
 * Periodically pulls provider-hosted DeDi files, verifies the digest chain, and feeds changed
 * catalogs into the discover /catalog/push pipeline. See docs/decentralized-catalog/.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(CrawlerProperties.class)
public class CrawlerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CrawlerApplication.class, args);
    }
}
