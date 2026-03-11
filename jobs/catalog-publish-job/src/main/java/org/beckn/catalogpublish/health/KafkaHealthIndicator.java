package org.beckn.catalogpublish.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

@Component
public class KafkaHealthIndicator implements HealthIndicator {

    private final KafkaListenerEndpointRegistry registry;

    public KafkaHealthIndicator(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        boolean allRunning = registry.getListenerContainers().stream()
                .allMatch(MessageListenerContainer::isRunning);
        return allRunning
                ? Health.up().withDetail("containers", "all running").build()
                : Health.down().withDetail("containers", "one or more stopped").build();
    }
}
