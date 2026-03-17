package org.beckn.seeker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimpleMessageProcessingTest {

    private MessageProcessingService messageProcessingService;
    private ObjectMapper objectMapper;

    @Mock
    private HttpService httpService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        messageProcessingService = new MessageProcessingService(objectMapper, httpService);
    }

    @Test
    void shouldProcessValidDiscoveryResponse() {
        // Given
        String discoveryResponse = """
            {
              "context": {
                "message_id": "msg-123",
                "bap_uri": "https://bap.example.com",
                "action": "on_search"
              },
              "catalog": {
                "providers": []
              }
            }
            """;

        // Mock successful HTTP call
        when(httpService.sendCallback(anyString())).thenReturn(true);

        // When
        String result = messageProcessingService.processMessage(discoveryResponse);

        // Then
        assertThat(result).isEqualTo("SUCCESS");
    }

    @Test
    void shouldThrowExceptionWhenHttpServiceFails() {
        // Given
        String discoveryResponse = """
            {
              "context": {
                "message_id": "msg-123",
                "bap_uri": "https://bap.example.com",
                "action": "on_search"
              },
              "catalog": {
                "providers": []
              }
            }
            """;

        // Mock failed HTTP call
        when(httpService.sendCallback(anyString())).thenReturn(false);

        // When & Then
        assertThatThrownBy(() -> messageProcessingService.processMessage(discoveryResponse))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Failed to send callback response");
    }

    @Test
    void shouldThrowExceptionForNullMessage() {
        // When & Then
        assertThatThrownBy(() -> messageProcessingService.processMessage(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message cannot be null");
    }

}
