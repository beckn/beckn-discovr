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

import static net.logstash.logback.argument.StructuredArguments.value;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventProducer {
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${topics.dlt}")
    private String dltTopic;

    /**
     * Send failed message to Dead Letter Topic with error metadata
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

        send(record);
        log.warn("{}", value("event", LogEvent.DLT_SENT),
                value("dltTopic", dltTopic),
                value("originalTopic", originalTopic),
                value("errorMessage", errorMessage));
    }

    /**
     * Generic send method for any topic
     */
    public void send(String key, String rawValue, String topic) {
        send(new ProducerRecord<>(topic, key, rawValue));
    }

    private void send(ProducerRecord<String, String> record) {
        kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("{}", value("event", LogEvent.DLT_FAILED),
                        value("topic", record.topic()), ex);
            } else {
                log.debug("{}", value("event", LogEvent.DLT_SENT),
                        value("topic", record.topic()));
            }
        });
    }
}
