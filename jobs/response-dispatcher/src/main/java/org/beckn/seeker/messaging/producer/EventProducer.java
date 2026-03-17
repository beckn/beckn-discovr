package org.beckn.seeker.messaging.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

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
    public void sendToDlt(String key, String value, String originalTopic, 
                         int originalPartition, long originalOffset, 
                         String errorMessage, String errorClass) {
        ProducerRecord<String, String> record = new ProducerRecord<>(dltTopic, key, value);
        
        // Add error metadata headers
        record.headers()
            .add(new RecordHeader("x-error", errorMessage.getBytes(StandardCharsets.UTF_8)))
            .add(new RecordHeader("x-error-class", errorClass.getBytes(StandardCharsets.UTF_8)))
            .add(new RecordHeader("x-original-topic", originalTopic.getBytes(StandardCharsets.UTF_8)))
            .add(new RecordHeader("x-original-partition", String.valueOf(originalPartition).getBytes(StandardCharsets.UTF_8)))
            .add(new RecordHeader("x-original-offset", String.valueOf(originalOffset).getBytes(StandardCharsets.UTF_8)));

        send(record);
        log.warn("Message sent to DLT topic: {} due to error: {}", dltTopic, errorMessage);
    }

    /**
     * Generic send method for any topic
     */
    public void send(String key, String value, String topic) {
        send(new ProducerRecord<>(topic, key, value));
    }

    private void send(ProducerRecord<String, String> record) {
        kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to send message to topic: {}", record.topic(), ex);
            } else {
                log.debug("Message sent successfully to topic: {}", record.topic());
            }
        });
    }
}
