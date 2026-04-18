package org.beckn.discover.util;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Kafka {@link ProducerInterceptor} that forwards the {@code tags} MDC field as a
 * Kafka record header on every outbound message.
 *
 * <p>Registered once via {@code interceptor.classes} in the producer config.
 * Zero changes are required at individual {@code kafkaTemplate.send()} call sites.
 *
 * <p>The header is omitted entirely when the {@code tags} MDC field is absent or blank,
 * so messages produced outside a tagged request carry no extra overhead.
 */
public class TagsProducerInterceptor implements ProducerInterceptor<String, String> {

    static final String HEADER_NAME = "tags";

    @Override
    public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
        String tags = MDC.get(HEADER_NAME);
        if (tags != null && !tags.isBlank()) {
            record.headers().add(HEADER_NAME, tags.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }

    @Override
    public void configure(Map<String, ?> configs) {
        // no-op — no configuration required
    }
}
