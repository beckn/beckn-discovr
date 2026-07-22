package org.beckn.crawler.source;

/**
 * One manifest source the crawler should crawl.
 *
 * @param manifestUrl full DeDi manifest URL (fetched directly — no path is appended)
 * @param displayName optional human label for logs (falls back to the manifest's own name)
 */
public record CrawlerSource(String manifestUrl, String displayName) {}
