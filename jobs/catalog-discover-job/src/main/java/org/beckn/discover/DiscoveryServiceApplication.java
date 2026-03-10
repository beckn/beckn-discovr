package org.beckn.discover;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Discovery Service Application
 * 
 * A Spring Kafka application that processes discovery events from Kafka,
 * interfaces with NLWeb natural language querying engine, and publishes
 * structured catalog responses back to Kafka.
 */
@SpringBootApplication
@EnableKafka
@EnableRetry
public class DiscoveryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServiceApplication.class, args);
    }
}
