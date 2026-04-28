package org.beckn.seeker.messaging.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.beckn.seeker.logging.LogEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static net.logstash.logback.argument.StructuredArguments.value;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventProducer {
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${topics.dlt}")
    private String dltTopic;

    private static final long DLT_SEND_TIMEOUT_SECONDS = 30;

    /**
     * Send failed message to Dead Letter Topic with error metadata.
     * Blocks until the broker confirms the publish so callers can safely ack
     * the original offset only after this method returns.
     *
     * @throws RuntimeException if the DLT publish fails or times out
     */
    public void sendToDlt(String key, String rawValue, String originalTopic,
                         int originalPartition, long originalOffset,
                         String errorMessage, String errorClass) {
        ProducerRecord<String, String> record = new ProducerRecord<>(dltTopic, key, rawValue);

        record.headers()
            .add(new RecordHeader("x-error", errorMessage.getBytes(StandardCharsets.UTF_8)))
            .add(new RecordHeader("x-error-class", errorClass.getBytes(StandardCharsets.UTF_8)))
            .add(new RecordHeader("x-original-topic", originalTopic.getBytes(StandardCharsets.UTF_8)))
            .add(new RecordHeader("x-original-partition", String.valueOf(originalPartition).getBytes(StandardCharsets.UTF_8)))
            .add(new RecordHeader("x-original-offset", String.valueOf(originalOffset).getBytes(StandardCharsets.UTF_8)));

        sendSync(record);
        log.warn("{}", value("event", LogEvent.DLT_SENT),
                value("dltTopic", dltTopic),
                value("originalTopic", originalTopic),
                value("errorMessage", errorMessage));
    }

    /**
     * Generic send method for any topic (fire-and-forget).
     */
    public void send(String key, String rawValue, String topic) {
        var record = new ProducerRecord<>(topic, key, rawValue);
        kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("{}", value("event", LogEvent.DLT_FAILED),
                        value("topic", record.topic()), ex);
            }
        });
    }

    /**
     * Synchronous send — blocks until the broker confirms the record.
     * Used for DLT publishes where the caller must not ack before confirmation.
     */
    private void sendSync(ProducerRecord<String, String> record) {
        try {
            kafkaTemplate.send(record).get(DLT_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            log.error("{}", value("event", LogEvent.DLT_FAILED),
                    value("topic", record.topic()), e.getCause());
            throw new RuntimeException("DLT publish failed for topic " + record.topic(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DLT publish interrupted for topic " + record.topic(), e);
        } catch (TimeoutException e) {
            log.error("{}", value("event", LogEvent.DLT_FAILED),
                    value("topic", record.topic()),
                    value("reason", "DLT publish timed out after " + DLT_SEND_TIMEOUT_SECONDS + "s"));
            throw new RuntimeException("DLT publish timed out for topic " + record.topic(), e);
        }
    }
}
