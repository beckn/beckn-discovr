package org.beckn.seeker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.beckn.seeker.logging.LogEvent;
import org.springframework.stereotype.Service;

import static net.logstash.logback.argument.StructuredArguments.value;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageProcessingService {

    private final ObjectMapper objectMapper;
    private final HttpService httpService;

    /**
     * Process incoming response message by sending it to the appropriate callback endpoint.
     *
     * @param message       the full JSON response body
     * @param subscriberId  org-level identity from Kafka header (may be null when auth disabled)
     * @param recordId      key-level identity from Kafka header (may be null when auth disabled)
     */
    public String processMessage(String message, String subscriberId, String recordId) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }

        log.debug("{}", value("event", LogEvent.CONSUMER_RECEIVED),
                value("messageLength", message.length()));

        boolean success = httpService.sendCallback(message, subscriberId, recordId);

        if (success) {
            log.info("{}", value("event", LogEvent.CONSUMER_PROCESSED),
                    value("result", "SUCCESS"));
            return "SUCCESS";
        } else {
            throw new RuntimeException("Failed to send callback response");
        }
    }
}
