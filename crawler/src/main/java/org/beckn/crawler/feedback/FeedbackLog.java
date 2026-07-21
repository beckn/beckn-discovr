package org.beckn.crawler.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.crawler.config.CrawlerProperties;
import org.beckn.crawler.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Append-only structured reject/skip log (design doc §5.10) — one JSON object per line.
 * {@code stage} ∈ {resolve, poll, validate, fetch, verify, push}; {@code reason} is a short code.
 */
@Component
public class FeedbackLog {

    private static final Logger log = LoggerFactory.getLogger(FeedbackLog.class);

    private final Path path;
    private final ObjectMapper mapper;

    public FeedbackLog(CrawlerProperties props, ObjectMapper mapper) {
        this.path = Path.of(props.feedbackLogPath());
        this.mapper = mapper;
    }

    public void record(String domain, String catalogId, String stage, String reason, String detail) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", java.time.Instant.now().toString());
        entry.put("domain", domain);
        entry.put("catalogId", catalogId);
        entry.put("stage", stage);
        entry.put("reason", reason);
        entry.put("detail", detail);
        // Always surface on the console for the demo, and persist for the record.
        log.warn(LogEvent.FEEDBACK, value("domain", domain), value("catalogId", catalogId),
                value("stage", stage), value("reason", reason), value("detail", detail));
        try {
            String line = mapper.writeValueAsString(entry) + System.lineSeparator();
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error(LogEvent.FEEDBACK_WRITE_FAILED, value("path", path.toString()), value("error", e.getMessage()));
        }
    }
}
