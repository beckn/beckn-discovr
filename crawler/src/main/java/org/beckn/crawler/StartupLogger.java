package org.beckn.crawler;

import org.beckn.crawler.config.CrawlerProperties;
import org.beckn.crawler.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.value;

/**
 * Emits the effective startup configuration once, right after boot, as a single structured event
 * so the first log line answers "what is this crawler about to do?" — everything from config.
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
        log.info(LogEvent.STARTUP_CONFIG,
                value("providers", props.providers()),
                value("manifestPath", props.wellKnownPath()),
                value("pushEndpoint", props.pushEndpoint()),
                value("pollInterval", props.pollInterval().toString()),
                value("httpTimeout", props.http().timeout().toString()),
                value("maxPartBytes", props.http().maxPartBytes()),
                value("feedbackLog", props.feedbackLogPath()));
    }
}
