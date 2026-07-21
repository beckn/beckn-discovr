package org.beckn.crawler.support;

import org.beckn.crawler.config.CrawlerProperties;

import java.time.Duration;
import java.util.List;

/** Builds CrawlerProperties for unit tests without loading the Spring context. */
public final class TestConfigs {

    private TestConfigs() {}

    public static CrawlerProperties props(String pushEndpoint) {
        return new CrawlerProperties(
                List.of("https://prov.example"),
                "/.well-known/dedi.json",
                pushEndpoint,
                Duration.ofMinutes(2),
                new CrawlerProperties.Http(Duration.ofSeconds(30), 10_485_760L, false),
                "./feedback.log");
    }
}
