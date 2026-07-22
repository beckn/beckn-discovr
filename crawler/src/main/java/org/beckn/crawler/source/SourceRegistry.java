package org.beckn.crawler.source;

import java.util.List;

/**
 * Supplies the current list of manifest sources to crawl. Which implementation is active is chosen
 * by {@code crawler.source} ({@code config} or {@code db}). Read fresh on every pass so runtime
 * changes (e.g. a new row added via the UI) take effect within a poll.
 */
public interface SourceRegistry {
    List<CrawlerSource> sources();
}
