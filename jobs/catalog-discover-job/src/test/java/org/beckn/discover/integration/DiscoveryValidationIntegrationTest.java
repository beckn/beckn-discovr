package org.beckn.discover.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.discover.service.validation.DiscoveryValidationService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Integration tests for {@link DiscoveryValidationService} using the REAL Beckn spec.
 *
 * <p>The schema URL is overridden via {@code @TestPropertySource} to point at the local
 * spec file so tests run without network access and against a known-good revision of the
 * spec.  No mocking of the schema loader.</p>
 *
 * <p>The Beckn spec {@code Context} schema is a {@code oneOf} between Context V2.0
 * and Context V1.0. Both branches now have {@code additionalProperties: false}.
 * Context V2.0 uses camelCase fields ({@code messageId}, {@code transactionId}, etc.)
 * and requires several fields.
 * Mixing camelCase V2.0 fields with V1.0-only fields (e.g. {@code domain}) causes both
 * branches to reject the payload — validation fails for mixed payloads.</p>
 */
@TestPropertySource(properties = {
    "discovery.schema.url=https://raw.githubusercontent.com/beckn/protocol-specifications-v2/refs/heads/main/api/v2.0.0/beckn.yaml"
})
class DiscoveryValidationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DiscoveryValidationService validationService;

    @Autowired
    private ObjectMapper objectMapper;

    // ── Valid requests ────────────────────────────────────────────────────────

    @Test
    void validV20RequestWithFilters_passesValidation() throws Exception {
        // Pure V2.0 context — only camelCase fields allowed by Context V2.0 schema.
        // No V1.0-only fields (domain, country, city, schemaContext) included.
        String payload = """
                {
                  "context": {
                    "action": "discover",
                    "version": "2.0.0",
                    "networkId": "ev-charging",
                    "transactionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d",
                    "ttl": "PT10M",
                    "timestamp": "2025-10-14T10:30:00.000Z"
                  },
                  "message": {
                    "intent": {
                      "filters": {
                        "type": "jsonpath",
                        "expression": "$.catalogs[*].resources[*]"
                      }
                    }
                  }
                }
                """;

        JsonNode node = objectMapper.readTree(payload);
        var result = validationService.validateDiscoverRequest(node);

        assertThat(result.isValid())
                .as("Valid v2.0 discover request with filters should pass validation; errors: %s",
                        result.getErrors())
                .isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void validRequestWithTextSearch_passesValidation() throws Exception {
        String payload = """
                {
                  "context": {
                    "action": "discover",
                    "version": "2.0.0",
                    "transactionId": "c1d2e3f4-a5b6-7890-cdef-012345678901",
                    "messageId": "d2e3f4a5-b6c7-8901-defa-123456789012",
                    "timestamp": "2025-10-14T10:30:00.000Z"
                  },
                  "message": {
                    "intent": {
                      "textSearch": "electric vehicle charger 60kW"
                    }
                  }
                }
                """;

        JsonNode node = objectMapper.readTree(payload);
        var result = validationService.validateDiscoverRequest(node);

        assertThat(result.isValid())
                .as("Valid textSearch request should pass validation; errors: %s", result.getErrors())
                .isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void validRequestWithSpatialIntent_passesValidation() throws Exception {
        String payload = """
                {
                  "context": {
                    "action": "discover",
                    "version": "2.0.0",
                    "transactionId": "e3f4a5b6-c7d8-9012-efab-234567890123",
                    "messageId": "f4a5b6c7-d8e9-0123-fabc-345678901234",
                    "timestamp": "2025-10-14T10:30:00.000Z"
                  },
                  "message": {
                    "intent": {
                      "spatial": [
                        {
                          "distanceMeters": 1000,
                          "geo": {
                            "type": "Point",
                            "coordinates": [77.5946, 12.9716]
                          }
                        }
                      ]
                    }
                  }
                }
                """;

        JsonNode node = objectMapper.readTree(payload);
        var result = validationService.validateDiscoverRequest(node);

        assertThat(result.isValid())
                .as("Valid spatial intent request should pass validation; errors: %s", result.getErrors())
                .isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    /**
     * Every GeoJSON geometry type in the spec's {@code type} enum, each with a valid all-numeric
     * form and a form carrying a single non-numeric coordinate. {@code (label, geometryJson, expectValid)}.
     */
    static Stream<Arguments> geometriesByType() {
        String R  = "[[77.5,12.9],[77.7,12.9],[77.7,13.0],[77.5,12.9]]";   // a valid closed ring
        String R2 = "[[77.6,13.0],[77.7,13.0],[77.7,13.1],[77.6,13.0]]";   // a second valid ring
        return Stream.of(
            // ── POSITIVE — valid coordinates across every geometry type (→ accepted) ──
            arguments("Point",                       "{\"type\":\"Point\",\"coordinates\":[77.6,12.9]}", true),
            arguments("Point integers",              "{\"type\":\"Point\",\"coordinates\":[77,12]}", true),
            arguments("Point negatives",             "{\"type\":\"Point\",\"coordinates\":[-77.6,-12.9]}", true),
            arguments("Point zero",                  "{\"type\":\"Point\",\"coordinates\":[0,0]}", true),
            arguments("Point altitude (3 numbers)",  "{\"type\":\"Point\",\"coordinates\":[77.6,12.9,920.5]}", true),
            arguments("LineString",                  "{\"type\":\"LineString\",\"coordinates\":[[77.6,12.9],[77.7,13.0]]}", true),
            arguments("LineString 3 points",         "{\"type\":\"LineString\",\"coordinates\":[[77.6,12.9],[77.7,13.0],[77.8,13.1]]}", true),
            arguments("Polygon",                     "{\"type\":\"Polygon\",\"coordinates\":[" + R + "]}", true),
            arguments("Polygon with hole",           "{\"type\":\"Polygon\",\"coordinates\":[" + R + "," + R2 + "]}", true),
            arguments("MultiPoint",                  "{\"type\":\"MultiPoint\",\"coordinates\":[[77.6,12.9],[77.7,13.0]]}", true),
            arguments("MultiLineString",             "{\"type\":\"MultiLineString\",\"coordinates\":[[[77.6,12.9],[77.7,13.0]],[[78.0,13.5],[78.1,13.6]]]}", true),
            arguments("MultiPolygon",                "{\"type\":\"MultiPolygon\",\"coordinates\":[[" + R + "]]}", true),
            arguments("MultiPolygon 2 polygons",     "{\"type\":\"MultiPolygon\",\"coordinates\":[[" + R + "],[" + R2 + "]]}", true),
            arguments("GeometryCollection",          "{\"type\":\"GeometryCollection\",\"geometries\":[{\"type\":\"Point\",\"coordinates\":[77.6,12.9]},{\"type\":\"Polygon\",\"coordinates\":[" + R + "]}]}", true),
            arguments("GeometryCollection mixed",    "{\"type\":\"GeometryCollection\",\"geometries\":[{\"type\":\"LineString\",\"coordinates\":[[77.6,12.9],[77.7,13.0]]},{\"type\":\"MultiPolygon\",\"coordinates\":[[" + R + "]]}]}", true),

            // ── NEGATIVE — non-numeric coordinate values (→ rejected) ──
            arguments("Point string lon",            "{\"type\":\"Point\",\"coordinates\":[\"77.6\",12.9]}", false),
            arguments("Point both strings",          "{\"type\":\"Point\",\"coordinates\":[\"77.6\",\"12.9\"]}", false),
            arguments("Point null ordinate",         "{\"type\":\"Point\",\"coordinates\":[77.6,null]}", false),
            arguments("Point boolean",               "{\"type\":\"Point\",\"coordinates\":[true,false]}", false),
            arguments("Point object ordinate",       "{\"type\":\"Point\",\"coordinates\":[{\"x\":1},12.9]}", false),
            arguments("LineString string",           "{\"type\":\"LineString\",\"coordinates\":[[77.6,12.9],[\"77.7\",13.0]]}", false),
            arguments("Polygon deep string",         "{\"type\":\"Polygon\",\"coordinates\":[[[77.5,12.9],[77.7,12.9],[77.7,\"13.0\"],[77.5,12.9]]]}", false),
            arguments("MultiPoint string",           "{\"type\":\"MultiPoint\",\"coordinates\":[[\"77.6\",12.9],[77.7,13.0]]}", false),
            arguments("MultiLineString string",      "{\"type\":\"MultiLineString\",\"coordinates\":[[[77.6,12.9],[77.7,\"13.0\"]]]}", false),
            arguments("MultiPolygon deep string",    "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[77.5,12.9],[77.7,12.9],[\"77.7\",13.0],[77.5,12.9]]]]}", false),
            arguments("GeometryCollection string member","{\"type\":\"GeometryCollection\",\"geometries\":[{\"type\":\"Point\",\"coordinates\":[\"77.6\",12.9]}]}", false),

            // ── NEGATIVE — empty arrays at any depth (→ rejected) ──
            arguments("Point empty",                 "{\"type\":\"Point\",\"coordinates\":[]}", false),
            arguments("Polygon nested-empty",        "{\"type\":\"Polygon\",\"coordinates\":[[]]}", false),
            arguments("Polygon empty-position",      "{\"type\":\"Polygon\",\"coordinates\":[[[77.6,12.9],[77.7,12.9],[]]]}", false),
            arguments("LineString empty-position",   "{\"type\":\"LineString\",\"coordinates\":[[77.6,12.9],[]]}", false),
            arguments("GeometryCollection empty member","{\"type\":\"GeometryCollection\",\"geometries\":[{\"type\":\"Point\",\"coordinates\":[]}]}", false),

            // ── NEGATIVE — under-length positions, a position needs >= 2 numbers (→ rejected) ──
            arguments("Point single-ordinate",       "{\"type\":\"Point\",\"coordinates\":[77.575]}", false),
            arguments("Polygon single-ordinate-pos", "{\"type\":\"Polygon\",\"coordinates\":[[[77.6,12.9],[77.7,13.0],[77.575]]]}", false),
            arguments("MultiPoint single-ordinate",  "{\"type\":\"MultiPoint\",\"coordinates\":[[77.6,12.9],[77.7]]}", false)
        );
    }

    @ParameterizedTest(name = "{0} -> valid={2}")
    @MethodSource("geometriesByType")
    void coordinateValidation_acrossAllGeometryTypes(String label, String geometryJson, boolean expectValid) throws Exception {
        String payload = ("""
                {
                  "context": { "action": "discover", "version": "2.0.0",
                    "transactionId": "e3f4a5b6-c7d8-9012-efab-234567890123",
                    "messageId": "f4a5b6c7-d8e9-0123-fabc-345678901234",
                    "timestamp": "2025-10-14T10:30:00.000Z" },
                  "message": { "intent": { "spatial": [
                    { "op": "S_INTERSECTS", "targets": "$.catalogs[*].provider.availableAt[*].geo", "geometry": %s }
                  ] } }
                }
                """).formatted(geometryJson);

        var result = validationService.validateDiscoverRequest(objectMapper.readTree(payload));
        assertThat(result.isValid()).as("%s (errors: %s)", label, result.getErrors()).isEqualTo(expectValid);
        if (!expectValid) {
            assertThat(result.getErrors()).anyMatch(m -> m.contains("coordinates must be numbers"));
        }
    }

    @Test
    void multiConstraintSpatialArray_allNumeric_passesValidation() throws Exception {
        // spec: spatial is an array of constraints (ANDed). Two valid Polygons must pass.
        String payload = """
                {
                  "context": {
                    "action": "discover", "version": "2.0.0",
                    "transactionId": "e3f4a5b6-c7d8-9012-efab-234567890123",
                    "messageId": "f4a5b6c7-d8e9-0123-fabc-345678901234",
                    "timestamp": "2025-10-14T10:30:00.000Z"
                  },
                  "message": { "intent": { "spatial": [
                    { "op": "S_INTERSECTS", "targets": "$.catalogs[*].provider.availableAt[*].geo",
                      "geometry": { "type": "Polygon", "coordinates": [[
                        [77.575,12.915],[77.625,12.915],[77.625,12.945],[77.575,12.945],[77.575,12.915]]] } },
                    { "op": "S_INTERSECTS", "targets": "$.catalogs[*].provider.availableAt[*].geo",
                      "geometry": { "type": "Polygon", "coordinates": [[
                        [77.630,12.870],[77.700,12.870],[77.700,12.920],[77.630,12.920],[77.630,12.870]]] } }
                  ] } }
                }
                """;
        var result = validationService.validateDiscoverRequest(objectMapper.readTree(payload));
        assertThat(result.isValid())
                .as("Two valid Polygon constraints should pass; errors: %s", result.getErrors()).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void multiConstraintSpatialArray_secondGeometryNonNumeric_failsValidation() throws Exception {
        // first Polygon valid, second has a string coordinate — the array must still be rejected
        String payload = """
                {
                  "context": {
                    "action": "discover", "version": "2.0.0",
                    "transactionId": "e3f4a5b6-c7d8-9012-efab-234567890123",
                    "messageId": "f4a5b6c7-d8e9-0123-fabc-345678901234",
                    "timestamp": "2025-10-14T10:30:00.000Z"
                  },
                  "message": { "intent": { "spatial": [
                    { "op": "S_INTERSECTS", "targets": "$.catalogs[*].provider.availableAt[*].geo",
                      "geometry": { "type": "Polygon", "coordinates": [[
                        [77.575,12.915],[77.625,12.915],[77.625,12.945],[77.575,12.945],[77.575,12.915]]] } },
                    { "op": "S_INTERSECTS", "targets": "$.catalogs[*].provider.availableAt[*].geo",
                      "geometry": { "type": "Polygon", "coordinates": [[
                        [77.630,12.870],[77.700,12.870],[77.700,"12.920"],[77.630,12.920],[77.630,12.870]]] } }
                  ] } }
                }
                """;
        var result = validationService.validateDiscoverRequest(objectMapper.readTree(payload));
        assertThat(result.isValid()).as("A bad geometry anywhere in the array must reject").isFalse();
        assertThat(result.getErrors()).anyMatch(m -> m.contains("coordinates must be numbers"));
    }

    @Test
    void pointWithAltitude_threeNumericOrdinates_passesValidation() throws Exception {
        // RFC 7946 positions may carry an optional altitude: [lon, lat, alt] — all numbers, valid
        String payload = """
                {
                  "context": {
                    "action": "discover", "version": "2.0.0",
                    "transactionId": "e3f4a5b6-c7d8-9012-efab-234567890123",
                    "messageId": "f4a5b6c7-d8e9-0123-fabc-345678901234",
                    "timestamp": "2025-10-14T10:30:00.000Z"
                  },
                  "message": { "intent": { "spatial": [
                    { "op": "S_DWITHIN", "distanceMeters": 1000,
                      "targets": "$.catalogs[*].provider.availableAt[*].geo",
                      "geometry": { "type": "Point", "coordinates": [77.6, 12.9, 920.0] } }
                  ] } }
                }
                """;
        var result = validationService.validateDiscoverRequest(objectMapper.readTree(payload));
        assertThat(result.isValid())
                .as("Point with numeric altitude should pass; errors: %s", result.getErrors()).isTrue();
    }

    // ── Context validation — missing required fields ──────────────────────────

    @Test
    void requestMissingContextEntirely_failsValidation() throws Exception {
        String payload = """
                {
                  "message": {
                    "intent": {
                      "filters": {
                        "type": "jsonpath",
                        "expression": "$.catalogs[*].resources[*]"
                      }
                    }
                  }
                }
                """;

        JsonNode node = objectMapper.readTree(payload);
        var result = validationService.validateDiscoverRequest(node);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors())
                .as("Missing context should produce a validation error")
                .anyMatch(e -> e.contains("context"));
    }

    // ── Intent validation — invalid message.intent ────────────────────────────

    @Test
    void requestWithMissingMessage_failsValidation() throws Exception {
        String payload = """
                {
                  "context": {
                    "action": "discover",
                    "version": "2.0.0",
                    "transactionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d",
                    "timestamp": "2025-10-14T10:30:00.000Z"
                  }
                }
                """;

        JsonNode node = objectMapper.readTree(payload);
        var result = validationService.validateDiscoverRequest(node);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors())
                .anyMatch(e -> e.contains("message"));
    }

    @Test
    void requestWithEmptyIntent_failsIntentSchemaValidation() throws Exception {
        // Intent schema requires at least one of: textSearch, filters, or spatial.
        // An empty intent object fails the anyOf constraint.
        String payload = """
                {
                  "context": {
                    "action": "discover",
                    "version": "2.0.0",
                    "transactionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d",
                    "timestamp": "2025-10-14T10:30:00.000Z"
                  },
                  "message": {
                    "intent": {}
                  }
                }
                """;

        JsonNode node = objectMapper.readTree(payload);
        var result = validationService.validateDiscoverRequest(node);

        // Empty intent is now rejected with a clear validation error — at least one
        // search criterion (textSearch, filters, or spatial) must be present.
        assertThat(result.isValid())
                .as("Empty intent must be rejected — at least one search criterion required")
                .isFalse();
        assertThat(result.getErrors())
                .as("Error should mention missing search criterion")
                .anyMatch(e -> e.contains("at least one search criterion"));
    }

    @Test
    void requestWithFiltersButMissingRequiredFilterType_failsIntentSchemaValidation() throws Exception {
        // Intent.filters schema requires both "type" and "expression" fields.
        // A filters object missing "type" should fail validation.
        String payload = """
                {
                  "context": {
                    "action": "discover",
                    "version": "2.0.0",
                    "transactionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d",
                    "timestamp": "2025-10-14T10:30:00.000Z"
                  },
                  "message": {
                    "intent": {
                      "filters": {
                        "expression": "$.catalogs[*].resources[*]"
                      }
                    }
                  }
                }
                """;

        JsonNode node = objectMapper.readTree(payload);
        var result = validationService.validateDiscoverRequest(node);

        assertThat(result.isValid())
                .as("Filters object missing required 'type' field should fail validation")
                .isFalse();
        assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    void requestWithRelativeFilterExpression_failsValidation() throws Exception {
        // The filter expression must be an absolute JSONPath (starting with $).
        String payload = """
                {
                  "context": {
                    "action": "discover",
                    "version": "2.0.0",
                    "transactionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d",
                    "timestamp": "2025-10-14T10:30:00.000Z"
                  },
                  "message": {
                    "intent": {
                      "filters": {
                        "type": "jsonpath",
                        "expression": "catalogs[*].resources"
                      }
                    }
                  }
                }
                """;

        JsonNode node = objectMapper.readTree(payload);
        var result = validationService.validateDiscoverRequest(node);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors())
                .anyMatch(e -> e.contains("absolute JSONPath"));
    }

    @Test
    void requestWithInvalidUuidTransactionId_failsValidation() throws Exception {
        String payload = """
                {
                  "context": {
                    "action": "discover",
                    "version": "2.0.0",
                    "transactionId": "not-a-valid-uuid",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d",
                    "timestamp": "2025-10-14T10:30:00.000Z"
                  },
                  "message": {
                    "intent": {
                      "textSearch": "charger"
                    }
                  }
                }
                """;

        JsonNode node = objectMapper.readTree(payload);
        var result = validationService.validateDiscoverRequest(node);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors())
                .anyMatch(e -> e.contains("transactionId") && e.contains("invalid uuid"));
    }

    @Test
    void requestWithNegativeDistanceMeters_failsValidation() throws Exception {
        String payload = """
                {
                  "context": {
                    "action": "discover",
                    "version": "2.0.0",
                    "transactionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d",
                    "timestamp": "2025-10-14T10:30:00.000Z"
                  },
                  "message": {
                    "intent": {
                      "spatial": [
                        {
                          "distanceMeters": -500,
                          "geo": {
                            "type": "Point",
                            "coordinates": [77.5946, 12.9716]
                          }
                        }
                      ]
                    }
                  }
                }
                """;

        JsonNode node = objectMapper.readTree(payload);
        var result = validationService.validateDiscoverRequest(node);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors())
                .anyMatch(e -> e.contains("distanceMeters") && e.contains(">= 0"));
    }

    // ── Action const validation ───────────────────────────────────────────────

    @Test
    void requestWithWrongAction_failsValidation() throws Exception {
        // The DiscoverAction/v2.0 full-envelope schema constrains context.action
        // to const "discover". Any other value must fail schema validation.
        String payload = """
                {
                  "context": {
                    "action": "catalog/publish",
                    "version": "2.0.0",
                    "transactionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d",
                    "timestamp": "2025-10-14T10:30:00.000Z"
                  },
                  "message": {
                    "intent": {
                      "textSearch": "electric vehicle charger"
                    }
                  }
                }
                """;

        JsonNode node = objectMapper.readTree(payload);
        var result = validationService.validateDiscoverRequest(node);

        assertThat(result.isValid())
                .as("Request with action 'catalog/publish' instead of 'discover' should fail validation; errors: %s",
                        result.getErrors())
                .isFalse();
        assertThat(result.getErrors()).isNotEmpty();
    }

    // ── Context oneOf bug documentation tests ────────────────────────────────

    // Context oneOf fixed — V1.0 now has additionalProperties: false
    @Test
    void pureV20ContextWithAllRequiredFields_wouldPassValidation() throws Exception {
        // A context with only V2.0 fields and all required fields should pass.
        String payload = """
                {
                  "context": {
                    "action": "discover",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d",
                    "timestamp": "2025-10-14T10:30:00.000Z",
                    "transactionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "version": "2.0.0"
                  },
                  "message": {
                    "intent": {
                      "textSearch": "electric vehicle charger"
                    }
                  }
                }
                """;

        JsonNode node = objectMapper.readTree(payload);
        var result = validationService.validateDiscoverRequest(node);

        // This test documents the expected behavior AFTER the spec fix:
        // a pure V2.0 context should pass validation.
        assertThat(result.isValid())
                .as("Pure V2.0 context should pass validation after spec oneOf fix; errors: %s",
                        result.getErrors())
                .isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    // Context oneOf fixed — V1.0 now has additionalProperties: false
    @Test
    void v20ContextMissingRequiredField_wouldFailContextValidation() throws Exception {
        // A V2.0 context missing a required field (e.g. transactionId) should fail validation.
        String payload = """
                {
                  "context": {
                    "action": "discover",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d",
                    "timestamp": "2025-10-14T10:30:00.000Z",
                    "version": "2.0.0"
                  },
                  "message": {
                    "intent": {
                      "textSearch": "electric vehicle charger"
                    }
                  }
                }
                """;

        JsonNode node = objectMapper.readTree(payload);
        var result = validationService.validateDiscoverRequest(node);

        // Missing transactionId should cause validation to fail.
        assertThat(result.isValid())
                .as("Context missing required transactionId should fail validation; errors: %s",
                        result.getErrors())
                .isFalse();
    }

    // ── Blank textSearch guard (PR #342) ──────────────────────────────────────
    // A blank/whitespace textSearch passes structural validation but then throws deep in the
    // async ElasticsearchTextSearchEngine ("query cannot be null or empty"), producing a failed
    // async query with no callback. The guard rejects it synchronously instead.

    static Stream<Arguments> blankTextSearchValues() {
        return Stream.of(
            arguments("empty string", ""),
            arguments("single space", " "),
            arguments("multiple spaces", "   ")
        );
    }

    @ParameterizedTest(name = "blank textSearch [{0}] -> rejected")
    @MethodSource("blankTextSearchValues")
    void blankTextSearch_failsValidationSynchronously(String label, String textSearch) throws Exception {
        String payload = ("""
                {
                  "context": {
                    "action": "discover",
                    "version": "2.0.0",
                    "transactionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d",
                    "timestamp": "2025-10-14T10:30:00.000Z"
                  },
                  "message": { "intent": { "textSearch": "%s" } }
                }
                """).formatted(textSearch);

        var result = validationService.validateDiscoverRequest(objectMapper.readTree(payload));

        assertThat(result.isValid())
                .as("Blank textSearch [%s] must be rejected synchronously; errors: %s", label, result.getErrors())
                .isFalse();
        assertThat(result.getErrors())
                .as("Error should state textSearch cannot be blank")
                .anyMatch(e -> e.contains("textSearch cannot be blank"));
    }

    @Test
    void textSearchWithSurroundingWhitespaceButContent_passesValidation() throws Exception {
        // "  organic  " is NOT blank — it carries content, so the blank guard must not reject it.
        String payload = """
                {
                  "context": {
                    "action": "discover",
                    "version": "2.0.0",
                    "transactionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d",
                    "timestamp": "2025-10-14T10:30:00.000Z"
                  },
                  "message": { "intent": { "textSearch": "  organic  " } }
                }
                """;

        var result = validationService.validateDiscoverRequest(objectMapper.readTree(payload));

        assertThat(result.isValid())
                .as("Non-blank textSearch with surrounding whitespace should pass; errors: %s", result.getErrors())
                .isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void blankTextSearch_evenWithValidFiltersPresent_failsValidation() throws Exception {
        // The guard is unconditional: a blank textSearch is rejected even when another valid
        // criterion (filters) already satisfies the "at least one criterion" check.
        String payload = """
                {
                  "context": {
                    "action": "discover",
                    "version": "2.0.0",
                    "transactionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "messageId": "b64609ca-4b8d-49ea-9db6-3f9c3d489c7d",
                    "timestamp": "2025-10-14T10:30:00.000Z"
                  },
                  "message": {
                    "intent": {
                      "textSearch": "",
                      "filters": { "type": "jsonpath", "expression": "$.catalogs[*].resources[*]" }
                    }
                  }
                }
                """;

        var result = validationService.validateDiscoverRequest(objectMapper.readTree(payload));

        assertThat(result.isValid())
                .as("Blank textSearch must be rejected even with valid filters present; errors: %s", result.getErrors())
                .isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("textSearch cannot be blank"));
    }
}
