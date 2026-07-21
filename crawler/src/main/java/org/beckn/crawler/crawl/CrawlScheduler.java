package org.beckn.crawler.crawl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the crawl cadence. Runs one pass on startup, then every {@code crawler.pollInterval}
 * (the interval that powers the "modified after N minutes" scenario). {@code fixedDelay} means
 * the next pass starts N after the previous one finishes — no overlap.
 *
 * <p>Enabled by default; tests set {@code crawler.scheduler.enabled=false} to drive passes
 * explicitly and keep assertions deterministic.
 */
@Component
@ConditionalOnProperty(name = "crawler.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class CrawlScheduler {

    private static final Logger log = LoggerFactory.getLogger(CrawlScheduler.class);

    private final Crawler crawler;

    public CrawlScheduler(Crawler crawler) {
        this.crawler = crawler;
    }

    @Scheduled(fixedDelayString = "${crawler.poll-interval}", initialDelay = 0)
    public void tick() {
        try {
            crawler.runPass();
        } catch (Exception e) {
            log.error("Crawl pass failed unexpectedly ({}) — will run again next interval", e.toString(), e);
        }
    }
}
