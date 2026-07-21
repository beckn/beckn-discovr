package org.beckn.crawler;

import org.beckn.crawler.config.CrawlerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Prints the effective startup configuration once, right after boot, so the very first thing in
 * the logs answers "what is this crawler about to do?" — providers, where it pushes, and how often.
 * Everything here is read from config; nothing is hardcoded.
 */
@Component
public class StartupLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    private final CrawlerProperties props;

    public StartupLogger(CrawlerProperties props) {
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logStartupConfig() {
        log.info("──────────────────────────────────────────────");
        log.info("Crawler started with startup config:");
        log.info("  providers ({})   : {}", props.providers().size(), props.providers());
        log.info("  manifest path    : {}", props.wellKnownPath());
        log.info("  push endpoint    : {}", props.pushEndpoint());
        log.info("  poll interval    : every {}", props.pollInterval());
        log.info("  http timeout     : {}", props.http().timeout());
        log.info("  max part size    : {} bytes", props.http().maxPartBytes());
        log.info("  feedback log     : {}", props.feedbackLogPath());
        log.info("First pass runs now, then repeats every {}.", props.pollInterval());
        log.info("──────────────────────────────────────────────");
    }
}
