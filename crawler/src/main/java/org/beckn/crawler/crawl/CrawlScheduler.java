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
 * Drives the two crawl cadences, both from config:
 * <ul>
 *   <li>manifest refresh every {@code crawler.manifestRefreshInterval} (long — provider identity
 *       + index location rarely change)</li>
 *   <li>index poll every {@code crawler.indexPollInterval} (short — catalog changes)</li>
 * </ul>
 * Both use fixed delay and run once immediately on startup (initial delay 0). The index poll
 * lazily learns the manifest on a cache miss, so no startup ordering is hardcoded.
 *
 * <p>Enabled by default; tests set {@code crawler.scheduler.enabled=false} to drive passes
 * explicitly and keep assertions deterministic.
 */
@Component
@ConditionalOnProperty(name = "crawler.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class CrawlScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CrawlScheduler.class);

    private final Crawler crawler;
    private final Duration manifestRefreshInterval;
    private final Duration indexPollInterval;

    public CrawlScheduler(Crawler crawler, CrawlerProperties props) {
        this.crawler = crawler;
        this.manifestRefreshInterval = props.manifestRefreshInterval();
        this.indexPollInterval = props.indexPollInterval();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::refreshManifests, manifestRefreshInterval);
        registrar.addFixedDelayTask(this::pollIndex, indexPollInterval);
    }

    void refreshManifests() {
        try {
            crawler.refreshManifests();
        } catch (Exception e) {
            log.error(LogEvent.MANIFEST_REFRESH_FAILED, value("error", e.toString()), e);
        }
    }

    void pollIndex() {
        try {
            crawler.runIndexPass();
        } catch (Exception e) {
            log.error(LogEvent.PASS_FAILED, value("error", e.toString()), e);
        }
    }
}
