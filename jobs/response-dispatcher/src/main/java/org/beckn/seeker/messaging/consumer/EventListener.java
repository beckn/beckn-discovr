package org.beckn.seeker.messaging.consumer;

import org.beckn.seeker.messaging.producer.EventProducer;
import org.beckn.seeker.service.MessageProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventListener {
    
    private final EventProducer eventProducer;
    private final MessageProcessingService messageProcessingService;

    @Value("${spring.kafka.listener.concurrency:1}")
    private String configuredConcurrency;

    @KafkaListener(
        topics = "${topics.input}",
        containerFactory = "kafkaListenerContainerFactory",
        concurrency = "${spring.kafka.listener.concurrency:1}"
    )
    public void listen(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("Using configured concurrency: {}", configuredConcurrency);
        String value = record.value();
        String key = record.key();
        
        log.info("Received message from topic: {}, partition: {}, offset: {}", 
                record.topic(), record.partition(), record.offset());
        log.debug("Message key: {}, Message length: {}", key, value != null ? value.length() : 0);
        
        try {
            // Process the discovery response message (send to BAP)
            String result = messageProcessingService.processMessage(value);
            
            log.info("Successfully processed discovery response from {} - Result: {}", record.topic(), result);
            
            // Commit offset only after successful processing
            ack.acknowledge();
            
        } catch (Exception e) {
            log.error("Error processing discovery response from topic {}: {}", record.topic(), e.getMessage(), e);

            // Use original Kafka key or a simple fallback
            String messageKey = key != null ? key : "unknown";

            try {
                // Send all failures to DLT after retries are exhausted
                eventProducer.sendToDlt(
                        messageKey,
                        value,
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        e.getMessage(),
                        e.getClass().getName()
                );
                log.warn("Failed after retries - sent to DLT");
            } catch (Exception dltEx) {
                log.error("Failed to send message to DLT — message will be lost: {}", dltEx.getMessage(), dltEx);
            } finally {
                // Always acknowledge so the listener is not stuck on a poison pill
                ack.acknowledge();
            }
        }
    }
    
}
