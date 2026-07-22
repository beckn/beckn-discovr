package org.beckn.crawler.source;

import org.beckn.crawler.config.CrawlerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Manifest sources from config ({@code crawler.providers}). Active by default (crawler.source=config).
 */
@Component
@ConditionalOnProperty(name = "crawler.source", havingValue = "config", matchIfMissing = true)
public class ConfigSourceRegistry implements SourceRegistry {

    private final List<CrawlerSource> sources;

    public ConfigSourceRegistry(CrawlerProperties props) {
        List<String> urls = props.providers() == null ? List.of() : props.providers();
        this.sources = urls.stream()
                .filter(u -> u != null && !u.isBlank())
                .map(u -> new CrawlerSource(u.trim(), null))
                .toList();
    }

    @Override
    public List<CrawlerSource> sources() {
        return sources;
    }
}
