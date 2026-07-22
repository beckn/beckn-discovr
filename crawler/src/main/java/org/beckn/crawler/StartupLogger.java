package org.beckn.crawler;

import org.beckn.crawler.config.CrawlerProperties;
import org.beckn.crawler.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
        List<Object> args = new ArrayList<>();
        args.add(value("source", props.source()));
        // Only relevant in config mode; in db mode the sources come from the crawler_source table.
        if ("config".equalsIgnoreCase(props.source())) {
            args.add(value("providers", props.providers()));
        }
        args.add(value("pushEndpoint", props.pushEndpoint()));
        args.add(value("manifestRefreshInterval", props.manifestRefreshInterval().toString()));
        args.add(value("indexPollInterval", props.indexPollInterval().toString()));
        args.add(value("httpTimeout", props.http().timeout().toString()));
        args.add(value("maxPartBytes", props.http().maxPartBytes()));
        args.add(value("feedbackLog", props.feedbackLogPath()));
        log.info(LogEvent.STARTUP_CONFIG, args.toArray());
    }
}
