package org.beckn.seeker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.retry.annotation.EnableRetry;

@EnableKafka
@EnableRetry
@SpringBootApplication
public class SeekerNotifierApplication {
    public static void main(String[] args) {
        SpringApplication.run(SeekerNotifierApplication.class, args);
    }
}
