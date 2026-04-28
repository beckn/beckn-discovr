package org.beckn.catalogpublish.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FieldExtractorIsRealResourceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── descriptor.name checks ──────────────────────────────────────────────

    @Test
    void isRealResource_returnsTrueWhenDescriptorHasName() {
        var resource = mapper.createObjectNode();
        var descriptor = mapper.createObjectNode();
        descriptor.put("name", "EV Station");
        resource.set("descriptor", descriptor);
        resource.put("id", "item-1");

        assertThat(FieldExtractor.isRealResource(resource)).isTrue();
    }

    @Test
    void isRealResource_returnsFalseWhenDescriptorAbsent() {
        var resource = mapper.createObjectNode();
        resource.put("id", "item-1");

        assertThat(FieldExtractor.isRealResource(resource)).isFalse();
    }

    @Test
    void isRealResource_returnsFalseWhenDescriptorIsNull() {
        var resource = mapper.createObjectNode();
        resource.put("id", "item-1");
        resource.putNull("descriptor");

        assertThat(FieldExtractor.isRealResource(resource)).isFalse();
    }

    @Test
    void isRealResource_returnsFalseWhenDescriptorIsEmptyObject() {
        // Empty descriptor with no name → not real
        var resource = mapper.createObjectNode();
        resource.set("descriptor", mapper.createObjectNode());
        resource.put("id", "item-1");

        assertThat(FieldExtractor.isRealResource(resource)).isFalse();
    }

    @Test
    void isRealResource_returnsFalseWhenDescriptorNameIsBlank() {
        var resource = mapper.createObjectNode();
        var descriptor = mapper.createObjectNode();
        descriptor.put("name", "   ");
        resource.set("descriptor", descriptor);

        assertThat(FieldExtractor.isRealResource(resource)).isFalse();
    }

    // ── resourceAttributes domain field checks ──────────────────────────────

    @Test
    void isRealResource_returnsTrueWhenResourceAttributesHasDomainFields() {
        // No descriptor, but resourceAttributes has fields beyond @type/@context → real
        var resource = mapper.createObjectNode();
        resource.put("id", "item-1");
        var attrs = mapper.createObjectNode();
        attrs.put("@type", "ChargingService");
        attrs.put("@context", "https://schema.org/ev");
        attrs.put("price", 18);  // domain field
        resource.set("resourceAttributes", attrs);

        assertThat(FieldExtractor.isRealResource(resource)).isTrue();
    }

    @Test
    void isRealResource_returnsFalseWhenResourceAttributesHasOnlySchemaFields() {
        // Minimal resource: only @type + @context, no domain fields → not real
        var resource = mapper.createObjectNode();
        resource.put("id", "item-1");
        var attrs = mapper.createObjectNode();
        attrs.put("@type", "ChargingService");
        attrs.put("@context", "https://schema.org/ev");
        resource.set("resourceAttributes", attrs);

        assertThat(FieldExtractor.isRealResource(resource)).isFalse();
    }

    @Test
    void isRealResource_returnsFalseWhenResourceAttributesHasOnlyType() {
        var resource = mapper.createObjectNode();
        resource.put("id", "item-1");
        var attrs = mapper.createObjectNode();
        attrs.put("@type", "ChargingService");
        resource.set("resourceAttributes", attrs);

        assertThat(FieldExtractor.isRealResource(resource)).isFalse();
    }

    // ── null / missing node checks ──────────────────────────────────────────

    @Test
    void isRealResource_returnsFalseForNullNode() {
        assertThat(FieldExtractor.isRealResource(null)).isFalse();
    }

    @Test
    void isRealResource_returnsFalseForMissingNode() {
        var missingNode = mapper.createObjectNode().path("nonexistent");
        assertThat(FieldExtractor.isRealResource(missingNode)).isFalse();
    }

    // ── combined checks ─────────────────────────────────────────────────────

    @Test
    void isRealResource_descriptorNameTakesPrecedenceOverEmptyAttributes() {
        // Has descriptor.name → real, even if resourceAttributes is empty
        var resource = mapper.createObjectNode();
        var descriptor = mapper.createObjectNode();
        descriptor.put("name", "iPhone 16");
        resource.set("descriptor", descriptor);
        resource.set("resourceAttributes", mapper.createObjectNode());

        assertThat(FieldExtractor.isRealResource(resource)).isTrue();
    }

    @Test
    void isRealResource_domainFieldSavesResourceWithoutDescriptor() {
        // No descriptor, but has domain field "weight" → real
        var resource = mapper.createObjectNode();
        resource.put("id", "item-1");
        var attrs = mapper.createObjectNode();
        attrs.put("weight", "170g");
        resource.set("resourceAttributes", attrs);

        assertThat(FieldExtractor.isRealResource(resource)).isTrue();
    }
}
