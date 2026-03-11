package org.beckn.catalogpublish.consumer;

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
            Acknowledgment ack) {
        metrics.recordProcessingTime(PUBLISH,
                () -> dispatch(raw, topic, offset, ack, PUBLISH, orchestrator::processPublish));
    }

    private void dispatch(String raw, String topic, long offset, Acknowledgment ack,
            CatalogOperation operation, Function<String, PublishOutcome> handler) {
        try {
            int rawByteLen = payloadSizeBytes(raw);
            if (rawByteLen > maxPayloadSize) {
                log.warn("msg.rejected.oversized op={} topic={} offset={} sizeBytes={} limit={}",
                        operation, topic, offset, rawByteLen, maxPayloadSize);
                rejectAndAck(raw, "Payload too large", operation, ack);
                return;
            }

            correlationContext.populateFallback();
            log.info("msg.received op={} topic={} offset={}", operation, topic, offset);

            PublishOutcome outcome = handler.apply(raw);
            List<ProcessingResult> results = outcome.results();

            if (!results.isEmpty() && results.stream().allMatch(r -> r.status() == ProcessingStatus.INTERNAL_ERROR)) {
                log.error("msg.all-internal-error op={} topic={} offset={} count={} — retrying",
                        operation, topic, offset, results.size());
                throw new RuntimeException("All " + results.size() + " catalog(s) returned INTERNAL_ERROR");
            }

            responsePublisher.publishResponse(outcome.context().contextNode(), results);
            metrics.recordMessageSuccess(operation);
            log.info("msg.processed op={} topic={} offset={} results={}", operation, topic, offset, results.size());
            ack.acknowledge();

        } catch (PayloadParseException | ValidationException e) {
            log.warn("msg.rejected op={} topic={} offset={} reason={}", operation, topic, offset,
                    ErrorSanitizer.sanitize(e));
            rejectAndAck(raw, ErrorSanitizer.sanitize(e), operation, ack);
        } catch (Exception e) {
            log.error("msg.failed op={} topic={} offset={} error={}", operation, topic, offset,
                    ErrorSanitizer.sanitize(e));
            throw e;
        } finally {
            correlationContext.clear();
        }
    }

    /** UTF-8 byte length; if char length already &gt; max, skips allocation and returns max+1. */
    private int payloadSizeBytes(String raw) {
        if (raw == null) return 0;
        return raw.length() > maxPayloadSize ? (int) maxPayloadSize + 1 : raw.getBytes(StandardCharsets.UTF_8).length;
    }

    private void rejectAndAck(String raw, String reason, CatalogOperation operation, Acknowledgment ack) {
        failedPublisher.publishFailed(raw, reason);
        metrics.recordMessageRejected(operation);
        ack.acknowledge();
    }
}
