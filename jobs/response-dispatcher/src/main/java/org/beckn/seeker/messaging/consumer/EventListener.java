package org.beckn.seeker.messaging.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.beckn.seeker.common.BecknFields;
import org.beckn.seeker.logging.BecknMdcContext;
import org.beckn.seeker.logging.LogEvent;
import org.beckn.seeker.logging.MdcField;
import org.beckn.seeker.messaging.producer.EventProducer;
import org.beckn.seeker.service.MessageProcessingService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.value;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventListener {

    private final EventProducer eventProducer;
    private final MessageProcessingService messageProcessingService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${topics.input}",
        containerFactory = "kafkaListenerContainerFactory",
        concurrency = "${spring.kafka.listener.concurrency:1}"
    )
    public void listen(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String rawValue = record.value();
        String key = record.key();

        // Extract tags header first so it is set for all subsequent log lines
        Header tagsHeader = record.headers().lastHeader(MdcField.TAGS);
        BecknMdcContext.setTags(tagsHeader != null ? tagsHeader.value() : null);

        // Read identity and MDC context from the JSON meta envelope.
        // Format: { "meta": { "subscriber_id": "...", "record_id": "..." }, "payload": { <Beckn response> } }
        // Identity lives in meta (consistent with Catalg PullResponsePublisher pattern) — no Kafka headers.
        String subscriberId = null;
        String recordId = null;
        try {
            if (rawValue != null) {
                JsonNode root = objectMapper.readTree(rawValue);
                JsonNode meta = root.path(BecknFields.META);
                if (!meta.isMissingNode()) {
                    subscriberId = meta.path(BecknFields.SUBSCRIBER_ID).asText(null);
                    recordId = meta.path(BecknFields.RECORD_ID).asText(null);
                }
                // MDC context lives inside payload.context (the Beckn response)
                JsonNode contextNode = root.path(BecknFields.PAYLOAD).path(BecknFields.CONTEXT);
                if (!contextNode.isMissingNode()) {
                    BecknMdcContext.populate(contextNode);
                }
            }
        } catch (Exception e) {
            log.error("{}", value("event", LogEvent.CONSUMER_ENVELOPE_PARSE_FAILED),
                    value("rawValue", truncate(rawValue, 200)),
                    value("errorMessage", e.getMessage()));
            // Short-circuit — non-parseable envelope cannot be processed; route directly to DLT
            // without traversing the full service stack on every retry.
            String messageKey = key != null ? key : "unknown";
            try {
                eventProducer.sendToDlt(messageKey, rawValue, record.topic(),
                        record.partition(), record.offset(), e.getMessage(), e.getClass().getName());
                log.warn("{}", value("event", LogEvent.DLT_SENT), value("key", messageKey));
                ack.acknowledge();
            } catch (Exception dltEx) {
                log.error("{}", value("event", LogEvent.DLT_FAILED),
                        value("errorMessage", dltEx.getMessage()), dltEx);
                throw new RuntimeException("DLT publish failed — offset not committed", dltEx);
            } finally {
                BecknMdcContext.clear();
            }
            return;
        }

        try {
            log.info("{}", value("event", LogEvent.CONSUMER_RECEIVED),
                    value("topic", record.topic()),
                    value("partition", record.partition()),
                    value("offset", record.offset()));

            String result = messageProcessingService.processMessage(rawValue, subscriberId, recordId);

            log.info("{}", value("event", LogEvent.CONSUMER_PROCESSED),
                    value("result", result));

            ack.acknowledge();

        } catch (Exception e) {
            log.error("{}", value("event", LogEvent.CONSUMER_ERROR),
                    value("topic", record.topic()),
                    value("errorMessage", e.getMessage()), e);

            String messageKey = key != null ? key : "unknown";

            try {
                eventProducer.sendToDlt(
                        messageKey,
                        rawValue,
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        e.getMessage(),
                        e.getClass().getName()
                );
                log.warn("{}", value("event", LogEvent.DLT_SENT),
                        value("topic", record.topic()),
                        value("key", messageKey));
                // Ack only after successful DLT publish. If DLT publish fails we must
                // NOT ack — throw so the container error handler retries or raises an alert.
                ack.acknowledge();
            } catch (Exception dltEx) {
                log.error("{}", value("event", LogEvent.DLT_FAILED),
                        value("errorMessage", dltEx.getMessage()), dltEx);
                // Re-throw so the container error handler sees the failure and does not
                // commit the offset. The message will be retried on the next poll.
                throw new RuntimeException("DLT publish failed — offset not committed", dltEx);
            }
        } finally {
            BecknMdcContext.clear();
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...[truncated]";
    }

}
