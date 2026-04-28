package org.beckn.catalogpublish.indexing.failure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.indexing.bulk.BulkIndexResult;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.catalog.elasticsearch.enabled", havingValue = "true")
public class EsFailurePublisher {

    private static final Logger log = LoggerFactory.getLogger(EsFailurePublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper                  mapper;
    private final String                        failureTopic;

    public EsFailurePublisher(KafkaTemplate<String, String> kafka,
                              ObjectMapper mapper,
                              AppProperties props) {
        this.kafka        = kafka;
        this.mapper       = mapper;
        this.failureTopic = props.catalog().elasticsearch().failureTopic();
    }

    public void publishFailures(String indexKey, String payloadJson,
                                List<BulkIndexResult.FailedDoc> failed) {
        for (BulkIndexResult.FailedDoc doc : failed) {
            publish(new EsFailureMessage(doc.resourceId(), doc.catalogId(), indexKey,
                    payloadJson, doc.reason(), Instant.now(), 1));
        }
    }

    public void republish(EsFailureMessage msg) {
        publish(msg.withNextAttempt());
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void publish(EsFailureMessage msg) {
        try {
            String json = mapper.writeValueAsString(msg);
            kafka.send(failureTopic, msg.catalogId(), json)
                 .whenComplete((r, ex) -> {
                     if (ex != null)
                         log.error("event={} resourceId={} error={}",
                                 LogEvent.KAFKA_FAILED, msg.resourceId(), ErrorSanitizer.sanitize(ex));
                 });
        } catch (Exception e) {
            log.error("event={} reason=serialize-failed resourceId={} error={}",
                    LogEvent.KAFKA_FAILED, msg.resourceId(), ErrorSanitizer.sanitize(e));
        }
    }
}
