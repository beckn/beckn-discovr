package org.beckn.catalogpublish.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.model.Item;
import org.beckn.catalogpublish.orchestration.CatalogPublishOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Beckn Item v2.1 schema support.
 *
 * All upstream payloads are now guaranteed v2.1 format. The API rejects old
 * v2.0 (beckn: prefixed) payloads before they reach the pipeline.
 *
 * Tests verify that:
 * 1. v2.1 items are persisted correctly (no schema_version/name/provider_id columns).
 * 2. Descriptor name is present in the stored payload JSON.
 * 3. schema_version does not appear in the payload JSON.
 */
class SchemaVersionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CatalogPublishOrchestrator orchestrator;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void publishV21Catalog_itemPersistedWithCorrectPayload() throws Exception {
        String message = """
                {
                  "context": {
                    "bppId": "bpp-v21.example.com",
                    "bppUri": "https://bpp-v21.example.com",
                    "messageId": "msg-v21-001",
                    "transactionId": "tx-v21-001"
                  },
                  "message": {
                    "catalogs": [
                      {
                        "id": "cat-v21-001",
                        "resources": [
                          {
                            "@context": "https://schema.beckn.io/",
                            "@type": "beckn:Resource",
                            "id": "item-v21-001",
                            "descriptor": {
                              "name": "Smart EV Charger v2.1",
                              "shortDesc": "Next-gen charger"
                            },
                            "provider": {"id": "provider-v21-001"},
                            "resourceAttributes": {
                              "@context": "https://example.org/charging.jsonld",
                              "@type": "ChargingService",
                              "connectorType": "CCS2",
                              "powerKw": 120
                            },
                            "constraints": [
                              {"type": "location", "value": "Bangalore"},
                              {"type": "vehicleType", "value": "EV"}
                            ],
                            "policies": [
                              {"type": "cancellation", "terms": "No refunds after session start"},
                              {"type": "payment", "terms": "Prepaid via app only"}
                            ]
                          }
                        ],
                        "offers": []
                      }
                    ]
                  }
                }
                """;

        var outcome = orchestrator.processPublish(message);
        assertThat(outcome.results()).hasSize(1);
        assertThat(outcome.results().get(0).catalogId()).isEqualTo("cat-v21-001");

        List<Item> items = itemRepository.findAll();
        assertThat(items).hasSize(1);

        Item item = items.get(0);
        assertThat(item.getId()).isEqualTo("item-v21-001");
        assertThat(item.getCatalogId()).isEqualTo("cat-v21-001");
        assertThat(item.getPayload())
                .as("Name must be present in payload")
                .contains("Smart EV Charger v2.1");

        JsonNode payload = objectMapper.readTree(item.getPayload());
        JsonNode itemNode = payload.path("catalogs").path(0).path("resources").path(0);
        assertThat(itemNode.isMissingNode()).isFalse();

        assertThat(item.getPayload())
                .as("schema_version must not be stored in the payload JSON")
                .doesNotContain("\"schema_version\"");
    }

    @Test
    void publishV21CatalogWithConstraintsAndPolicies_nameInPayload() throws Exception {
        String message = """
                {
                  "context": {
                    "bppId": "bpp-v21b.example.com",
                    "bppUri": "https://bpp-v21b.example.com",
                    "messageId": "msg-v21-002",
                    "transactionId": "tx-v21-002"
                  },
                  "message": {
                    "catalogs": [
                      {
                        "id": "cat-v21-002",
                        "resources": [
                          {
                            "@context": "https://schema.beckn.io/",
                            "@type": "beckn:Resource",
                            "id": "item-v21-002",
                            "descriptor": {
                              "name": "Slot Booking Service",
                              "shortDesc": "Time-slot-based EV charging"
                            },
                            "provider": {"id": "provider-v21-002"},
                            "resourceAttributes": {
                              "@context": "https://example.org/slot.jsonld",
                              "@type": "SlotBookingService",
                              "duration": "30min",
                              "powerKw": 22
                            },
                            "constraints": [
                              {"type": "advance", "value": "24h minimum"}
                            ],
                            "policies": [
                              {"type": "cancellation", "terms": "Free cancellation 2h before"}
                            ]
                          }
                        ],
                        "offers": []
                      }
                    ]
                  }
                }
                """;

        orchestrator.processPublish(message);

        List<Item> items = itemRepository.findAll();
        assertThat(items).hasSize(1);
        Item item = items.get(0);

        assertThat(item.getPayload()).contains("Slot Booking Service");
        assertThat(item.getPayload()).doesNotContain("\"schema_version\"");
    }

    @Test
    void publishMultipleV21Items_allPersistedWithCorrectPayload() throws Exception {
        String message = """
                {
                  "context": {
                    "bppId": "bpp-multi.example.com",
                    "bppUri": "https://bpp-multi.example.com",
                    "messageId": "msg-multi-001",
                    "transactionId": "tx-multi-001"
                  },
                  "message": {
                    "catalogs": [
                      {
                        "id": "cat-multi-001",
                        "resources": [
                          {
                            "@context": "https://schema.beckn.io/",
                            "@type": "beckn:Resource",
                            "id": "item-multi-001",
                            "descriptor": {"name": "CCS2 Charger"},
                            "provider": {"id": "prov-001"},
                            "resourceAttributes": {
                              "@context": "https://ctx.example.org",
                              "@type": "ChargingService",
                              "connectorType": "CCS2"
                            }
                          },
                          {
                            "@context": "https://schema.beckn.io/",
                            "@type": "beckn:Resource",
                            "id": "item-multi-002",
                            "descriptor": {"name": "Type2 Charger"},
                            "provider": {"id": "prov-001"},
                            "resourceAttributes": {
                              "@context": "https://ctx.example.org",
                              "@type": "ChargingService",
                              "connectorType": "Type2"
                            },
                            "constraints": [{"type": "zone", "value": "Bangalore"}]
                          }
                        ],
                        "offers": []
                      }
                    ]
                  }
                }
                """;

        orchestrator.processPublish(message);

        List<Item> items = itemRepository.findAll();
        assertThat(items).hasSize(2);

        Item item1 = items.stream()
                .filter(i -> "item-multi-001".equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("item-multi-001 not found"));
        Item item2 = items.stream()
                .filter(i -> "item-multi-002".equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("item-multi-002 not found"));

        assertThat(item1.getPayload())
                .as("item1 payload must contain descriptor name")
                .contains("CCS2 Charger");
        assertThat(item1.getCatalogId()).isEqualTo("cat-multi-001");

        assertThat(item2.getPayload())
                .as("item2 payload must contain descriptor name")
                .contains("Type2 Charger");
        assertThat(item2.getCatalogId()).isEqualTo("cat-multi-001");
    }

    @Test
    void publishV21Catalog_schemaVersionNotInPayloadJson() throws Exception {
        String message = """
                {
                  "context": {
                    "bppId": "bpp-clean.example.com",
                    "bppUri": "https://bpp-clean.example.com",
                    "messageId": "msg-clean-001",
                    "transactionId": "tx-clean-001"
                  },
                  "message": {
                    "catalogs": [
                      {
                        "id": "cat-clean-001",
                        "resources": [
                          {
                            "@type": "beckn:Resource",
                            "id": "item-clean-001",
                            "descriptor": {"name": "Clean Item"},
                            "provider": {"id": "prov-clean-001"},
                            "resourceAttributes": {
                              "@type": "ChargingService",
                              "connectorType": "CCS2"
                            }
                          }
                        ],
                        "offers": []
                      }
                    ]
                  }
                }
                """;

        orchestrator.processPublish(message);

        List<Item> items = itemRepository.findAll();
        assertThat(items).hasSize(1);
        Item item = items.get(0);

        assertThat(item.getCatalogId()).isEqualTo("cat-clean-001");
        assertThat(item.getPayload())
                .as("schema_version must not be stored in the payload JSON")
                .doesNotContain("\"schema_version\"");
        assertThat(item.getPayload())
                .as("legacy beckn: prefixed field keys must not appear in stored payloads")
                .doesNotContain("\"beckn:items\"")
                .doesNotContain("\"beckn:descriptor\"")
                .doesNotContain("\"beckn:provider\"")
                .doesNotContain("\"beckn:offers\"");
    }
}
