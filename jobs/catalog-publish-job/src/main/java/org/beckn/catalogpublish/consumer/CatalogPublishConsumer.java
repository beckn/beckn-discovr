package org.beckn.catalogpublish.consumer;

import org.beckn.catalogpublish.common.ErrorMessages;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.dto.CatalogOperation;
import org.beckn.catalogpublish.dto.ProcessingResult;
import org.beckn.catalogpublish.dto.ProcessingStatus;
import org.beckn.catalogpublish.dto.PublishOutcome;
import org.beckn.catalogpublish.exception.PayloadParseException;
import org.beckn.catalogpublish.exception.ValidationException;
import org.beckn.catalogpublish.messaging.FailedMessagePublisher;
import org.beckn.catalogpublish.messaging.ResponsePublisher;
import org.beckn.catalogpublish.metrics.CatalogPublishMetrics;
import org.beckn.catalogpublish.orchestration.CatalogPublishOrchestrator;
import org.beckn.catalogpublish.logging.LogEvent;
import org.beckn.catalogpublish.util.CorrelationContext;
import org.beckn.catalogpublish.util.ErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import static org.beckn.catalogpublish.dto.CatalogOperation.PUBLISH;

@Component
public class CatalogPublishConsumer {

    private static final Logger log = LoggerFactory.getLogger(CatalogPublishConsumer.class);

    private final CatalogPublishOrchestrator orchestrator;
    private final ResponsePublisher responsePublisher;
    private final FailedMessagePublisher failedPublisher;
    private final CatalogPublishMetrics metrics;
    private final CorrelationContext correlationContext;
    private final long maxPayloadSize;

    public CatalogPublishConsumer(CatalogPublishOrchestrator orchestrator,
            ResponsePublisher responsePublisher,
            FailedMessagePublisher failedPublisher,
            CatalogPublishMetrics metrics,
            CorrelationContext correlationContext,
            AppProperties props) {
        this.orchestrator = orchestrator;
        this.responsePublisher = responsePublisher;
        this.failedPublisher = failedPublisher;
        this.metrics = metrics;
        this.correlationContext = correlationContext;
        this.maxPayloadSize = props.catalog().maxPayloadSize();
    }

    @KafkaListener(topics = "${app.messaging.topics.ingestion-requests}", groupId = "${app.messaging.consumer.group-id}", containerFactory = "kafkaListenerContainerFactory")
    public void onPublishMessage(
            @Payload String raw,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(value = "tags", required = false) byte[] tagsHeader,
            Acknowledgment ack) {
        correlationContext.setTags(tagsHeader);
        metrics.recordProcessingTime(PUBLISH,
                () -> dispatch(raw, topic, offset, ack, PUBLISH, orchestrator::processPublish));
    }

    private void dispatch(String raw, String topic, long offset, Acknowledgment ack,
            CatalogOperation operation, Function<String, PublishOutcome> handler) {
        try {
            long rawByteLen = payloadSizeBytes(raw);
            if (rawByteLen > maxPayloadSize) {
                log.warn("event={} op={} topic={} offset={} sizeBytes={} limit={}",
                        LogEvent.CONSUMER_REJECTED, operation, topic, offset, rawByteLen, maxPayloadSize);
                rejectAndAck(raw, ErrorMessages.REQUEST_TOO_LARGE, operation, ack);
                return;
            }

            correlationContext.populateFallback();
            log.info("event={} op={} topic={} offset={}", LogEvent.CONSUMER_RECEIVED, operation, topic, offset);

            PublishOutcome outcome = handler.apply(raw);
            List<ProcessingResult> results = outcome.results();

            // Retry if ANY catalog has INTERNAL_ERROR — partial failure must not be silently acked.
            // A failed catalog not re-queued is permanently lost; retry allows recovery.
            long errorCount = results.stream().filter(r -> r.status() == ProcessingStatus.INTERNAL_ERROR).count();
            if (!results.isEmpty() && errorCount > 0) {
                log.error("event={} op={} topic={} offset={} errorCount={} totalCount={} — retrying",
                        LogEvent.CONSUMER_ERROR, operation, topic, offset, errorCount, results.size());
                throw new RuntimeException(errorCount + " of " + results.size() + " catalog(s) returned INTERNAL_ERROR");
            }

            responsePublisher.publishResponse(outcome.context().contextNode(), results);
            metrics.recordMessageSuccess(operation);
            log.info("event={} op={} topic={} offset={} results={}", LogEvent.CONSUMER_PROCESSED, operation, topic, offset, results.size());
            ack.acknowledge();

        } catch (PayloadParseException | ValidationException e) {
            log.warn("event={} op={} topic={} offset={} reason={}", LogEvent.CONSUMER_REJECTED, operation, topic, offset,
                    ErrorSanitizer.sanitize(e));
            rejectAndAck(raw, ErrorSanitizer.sanitize(e), operation, ack);
        } catch (Exception e) {
            log.error("event={} op={} topic={} offset={} error={}", LogEvent.CONSUMER_ERROR, operation, topic, offset,
                    ErrorSanitizer.sanitize(e));
            throw e;
        } finally {
            correlationContext.clear();
        }
    }

    /** UTF-8 byte length; if char length already &gt; max, skips allocation and returns max+1. */
    private long payloadSizeBytes(String raw) {
        if (raw == null) return 0;
        return raw.length() > maxPayloadSize ? maxPayloadSize + 1 : raw.getBytes(StandardCharsets.UTF_8).length;
    }

    private void rejectAndAck(String raw, String reason, CatalogOperation operation, Acknowledgment ack) {
        failedPublisher.publishFailed(raw, reason);
        metrics.recordMessageRejected(operation);
        ack.acknowledge();
    }
}
