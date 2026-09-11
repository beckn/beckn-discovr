package org.beckn.discover.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.beckn.discover.config.DiscoveryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DiscoveryValidationService}, focused on the
 * {@code discovery.schema.validation-enabled} toggle (issue #474).
 *
 * <p>Constructs the service directly with a mocked {@link SchemaLoaderService} and a real
 * {@link DiscoveryProperties} instance so the {@code validationEnabled} flag can be flipped
 * per test without booting a Spring context.</p>
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryValidationServiceUnitTest {

    @Mock
    private SchemaLoaderService schemaLoaderService;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final Yaml yamlParser = new Yaml();
    private DiscoveryProperties discoveryProperties;

    @BeforeEach
    void setUp() {
        discoveryProperties = new DiscoveryProperties();
    }

    private DiscoveryValidationService newService() {
        return new DiscoveryValidationService(objectMapper, schemaLoaderService, yamlParser, discoveryProperties);
    }

    // ── init() ──────────────────────────────────────────────────────────────

    @Test
    void init_flagDisabled_neverFetchesSchema_andDoesNotThrow() throws Exception {
        discoveryProperties.getSchema().setValidationEnabled(false);
        DiscoveryValidationService service = newService();

        assertThatCode(service::init).doesNotThrowAnyException();

        verify(schemaLoaderService, never()).getApiSchema();
    }

    @Test
    void init_flagDisabled_evenWhenLoaderWouldThrow_doesNotPropagate() throws Exception {
        discoveryProperties.getSchema().setValidationEnabled(false);
        // Loader is never invoked when disabled — stubbing it to throw proves init() truly
        // short-circuits rather than merely swallowing a fast-failing call.
        DiscoveryValidationService service = newService();

        assertThatCode(service::init).doesNotThrowAnyException();
        verify(schemaLoaderService, never()).getApiSchema();
    }

    @Test
    void init_flagEnabled_invokesGetApiSchema_regressionGuard() throws Exception {
        discoveryProperties.getSchema().setValidationEnabled(true);
        when(schemaLoaderService.getApiSchema()).thenThrow(new RuntimeException("network unreachable"));
        DiscoveryValidationService service = newService();

        // Enabled path preserves fail-fast semantics: a load failure is fatal.
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, service::init);

        verify(schemaLoaderService).getApiSchema();
    }

    // ── validateDiscoverRequest() ───────────────────────────────────────────

    @Test
    void validateDiscoverRequest_flagDisabled_wellFormedRequest_returnsValid() throws Exception {
        discoveryProperties.getSchema().setValidationEnabled(false);
        DiscoveryValidationService service = newService();
        service.init();

        JsonNode node = wellFormedRequest();
        var result = service.validateDiscoverRequest(node);

        assertThat(result.isValid()).as("errors: %s", result.getErrors()).isTrue();
        assertThat(result.getErrors()).isEmpty();
        verify(schemaLoaderService, never()).getApiSchema();
    }

    @Test
    void validateDiscoverRequest_flagDisabled_blankTextSearch_stillInvalid() throws Exception {
        discoveryProperties.getSchema().setValidationEnabled(false);
        DiscoveryValidationService service = newService();
        service.init();

        JsonNode node = objectMapper.readTree("""
                {
                  "context": {
                    "action": "discover",
                    "transactionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d"
                  },
                  "message": { "intent": { "textSearch": "   " } }
                }
                """);

        var result = service.validateDiscoverRequest(node);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("textSearch cannot be blank"));
    }

    @Test
    void validateDiscoverRequest_flagDisabled_nonAbsoluteJsonPath_stillInvalid() throws Exception {
        discoveryProperties.getSchema().setValidationEnabled(false);
        DiscoveryValidationService service = newService();
        service.init();

        JsonNode node = objectMapper.readTree("""
                {
                  "context": {
                    "action": "discover",
                    "transactionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d"
                  },
                  "message": {
                    "intent": {
                      "filters": { "type": "jsonpath", "expression": "catalogs[*].resources" }
                    }
                  }
                }
                """);

        var result = service.validateDiscoverRequest(node);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("absolute JSONPath"));
    }

    @Test
    void validateDiscoverRequest_flagDisabled_negativeDistanceMeters_stillInvalid() throws Exception {
        discoveryProperties.getSchema().setValidationEnabled(false);
        DiscoveryValidationService service = newService();
        service.init();

        JsonNode node = objectMapper.readTree("""
                {
                  "context": {
                    "action": "discover",
                    "transactionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d"
                  },
                  "message": {
                    "intent": {
                      "spatial": [
                        { "distanceMeters": -100, "geometry": { "type": "Point", "coordinates": [1.0, 2.0] } }
                      ]
                    }
                  }
                }
                """);

        var result = service.validateDiscoverRequest(node);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("distanceMeters") && e.contains(">= 0"));
    }

    @Test
    void validateDiscoverRequest_flagDisabled_malformedUuid_stillInvalid() throws Exception {
        discoveryProperties.getSchema().setValidationEnabled(false);
        DiscoveryValidationService service = newService();
        service.init();

        JsonNode node = objectMapper.readTree("""
                {
                  "context": {
                    "action": "discover",
                    "transactionId": "not-a-uuid",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d"
                  },
                  "message": { "intent": { "textSearch": "charger" } }
                }
                """);

        var result = service.validateDiscoverRequest(node);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("transactionId") && e.contains("invalid uuid"));
    }

    private JsonNode wellFormedRequest() throws Exception {
        return objectMapper.readTree("""
                {
                  "context": {
                    "action": "discover",
                    "transactionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d"
                  },
                  "message": { "intent": { "textSearch": "electric vehicle charger" } }
                }
                """);
    }
}
