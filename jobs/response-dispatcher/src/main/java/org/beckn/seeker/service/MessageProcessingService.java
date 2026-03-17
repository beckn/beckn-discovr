package org.beckn.seeker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageProcessingService {
    
    private final ObjectMapper objectMapper;
    private final HttpService httpService;

    /**
     * Process incoming response message by sending it to the appropriate callback endpoint
     */
    public String processMessage(String message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }

        log.debug("Processing response message of length: {}", message.length());

        // Send to callback endpoint (includes JSON validation and event structure validation)
        boolean success = httpService.sendCallback(message);

        if (success) {
            log.info("Successfully processed response message");
            return "SUCCESS";
        } else {
            throw new RuntimeException("Failed to send callback response");
        }
    }


}
