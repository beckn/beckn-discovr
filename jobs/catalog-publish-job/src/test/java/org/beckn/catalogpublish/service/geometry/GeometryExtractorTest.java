package org.beckn.catalogpublish.service.geometry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.model.ItemLocationCollection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeometryExtractorTest {

    private final GeometryExtractor extractor = new GeometryExtractor(new ObjectMapper());

    @Test
    void extractLocations_emptyPayloadReturnsEmpty() {
        List<ItemLocationCollection> out = extractor.extractLocations("item1", "{}");
        assertThat(out).isEmpty();
    }

    @Test
    void extractLocations_parsesGps() {
        String payload = "{\"catalogs\":[{\"resources\":[{\"availableAt\":[{\"gps\":\"12.34,56.78\"}]}]}]}";
        List<ItemLocationCollection> out = extractor.extractLocations("item1", payload);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getId().getItemId()).isEqualTo("item1");
        assertThat(out.get(0).getId().getPath()).isEqualTo("$.catalogs[*].resources[*].availableAt[*].gps");
        assertThat(out.get(0).getGeom()).isNotNull();
    }

    @Test
    void extractLocations_invalidJsonReturnsEmpty() {
        List<ItemLocationCollection> out = extractor.extractLocations("item1", "not json");
        assertThat(out).isEmpty();
    }

    @Test
    void extractLocations_parsesGeoJsonPoint() {
        String payload = "{\"catalogs\":[{\"resources\":[{\"availableAt\":[{\"geo\":{\"type\":\"Point\",\"coordinates\":[77.5946,12.9716]}}]}]}]}";
        List<ItemLocationCollection> out = extractor.extractLocations("item1", payload);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getId().getItemId()).isEqualTo("item1");
        assertThat(out.get(0).getId().getPath()).isEqualTo("$.catalogs[*].resources[*].availableAt[*].geo");
        assertThat(out.get(0).getGeom()).isNotNull();
    }

    @Test
    void extractLocations_multipleAvailableAt_allUseWildcardPath() {
        String payload = "{\"catalogs\":[{\"resources\":[{\"availableAt\":["
                + "{\"geo\":{\"type\":\"Point\",\"coordinates\":[77.59,12.97]}},"
                + "{\"geo\":{\"type\":\"Point\",\"coordinates\":[77.61,12.91]}}]}]}]}";
        List<ItemLocationCollection> out = extractor.extractLocations("item1", payload);
        assertThat(out).hasSize(2);
        // All array positions use [*] so stored paths match the discovery API's JSONPath wildcard queries.
        assertThat(out.get(0).getId().getPath()).isEqualTo("$.catalogs[*].resources[*].availableAt[*].geo");
        assertThat(out.get(1).getId().getPath()).isEqualTo("$.catalogs[*].resources[*].availableAt[*].geo");
    }
}
