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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
        // Given - Beckn v2.0 camelCase context fields
        String discoveryResponse = """
            {
              "context": {
                "messageId": "msg-123",
                "bapUri": "https://bap.example.com",
                "action": "on_search"
              },
              "catalog": {
                "providers": []
              }
            }
            """;

        // Mock successful HTTP call
        when(httpService.sendCallback(anyString(), isNull(), isNull())).thenReturn(true);

        // When
        String result = messageProcessingService.processMessage(discoveryResponse, null, null);

        // Then
        assertThat(result).isEqualTo("SUCCESS");
    }

    @Test
    void shouldThrowExceptionWhenHttpServiceFails() {
        // Given - Beckn v2.0 camelCase context fields
        String discoveryResponse = """
            {
              "context": {
                "messageId": "msg-123",
                "bapUri": "https://bap.example.com",
                "action": "on_search"
              },
              "catalog": {
                "providers": []
              }
            }
            """;

        // Mock failed HTTP call
        when(httpService.sendCallback(anyString(), isNull(), isNull())).thenReturn(false);

        // When & Then
        assertThatThrownBy(() -> messageProcessingService.processMessage(discoveryResponse, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Failed to send callback response");
    }

    @Test
    void shouldForwardIdentityToHttpService() {
        // Given — non-null identity values
        String discoveryResponse = """
            {
              "context": {
                "messageId": "msg-789",
                "action": "on_discover"
              },
              "catalog": {
                "providers": []
              }
            }
            """;

        when(httpService.sendCallback(anyString(), eq("sub-1"), eq("rec-1"))).thenReturn(true);

        // When
        String result = messageProcessingService.processMessage(discoveryResponse, "sub-1", "rec-1");

        // Then — identity forwarded through to HttpService
        assertThat(result).isEqualTo("SUCCESS");
    }

    @Test
    void shouldThrowExceptionForNullMessage() {
        // When & Then
        assertThatThrownBy(() -> messageProcessingService.processMessage(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message cannot be null");
    }

}
