package org.beckn.crawler.crawl;

import org.beckn.crawler.config.CrawlerProperties;
import org.beckn.crawler.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static net.logstash.logback.argument.StructuredArguments.value;

/**
 * Drives the crawl cadence. Runs one pass immediately on startup, then every
 * {@code crawler.pollInterval} (the interval that powers the "modified after N minutes" scenario).
 * Fixed-delay means the next pass starts N after the previous one finishes — no overlap.
 *
 * <p>The interval is taken from the already-parsed {@link Duration} so config stays human-friendly
 * ({@code 2m}, {@code 30s}) — a raw string like "2m" is not a valid {@code @Scheduled} fixedDelay.
 *
 * <p>Enabled by default; tests set {@code crawler.scheduler.enabled=false} to drive passes
 * explicitly and keep assertions deterministic.
 */
@Component
@ConditionalOnProperty(name = "crawler.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class CrawlScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CrawlScheduler.class);

    private final Crawler crawler;
    private final Duration pollInterval;

    public CrawlScheduler(Crawler crawler, CrawlerProperties props) {
        this.crawler = crawler;
        this.pollInterval = props.pollInterval();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        // Fixed delay = pollInterval, first run immediately (initialDelay = 0).
        registrar.addFixedDelayTask(this::tick, pollInterval);
    }

    void tick() {
        try {
            crawler.runPass();
        } catch (Exception e) {
            log.error(LogEvent.PASS_FAILED, value("error", e.toString()), e);
        }
    }
}
