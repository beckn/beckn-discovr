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
        List<ItemLocationCollection> out = extractor.extractLocations("item1", "cat-1","{}");
        assertThat(out).isEmpty();
    }

    @Test
    void extractLocations_parsesGps() {
        String payload = "{\"catalogs\":[{\"resources\":[{\"availableAt\":[{\"gps\":\"12.34,56.78\"}]}]}]}";
        List<ItemLocationCollection> out = extractor.extractLocations("item1", "cat-1",payload);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getId().getItemId()).isEqualTo("item1");
        assertThat(out.get(0).getId().getPath()).isEqualTo("$.catalogs[*].resources[*].availableAt[*].gps");
        assertThat(out.get(0).getGeom()).isNotNull();
    }

    @Test
    void extractLocations_invalidJsonReturnsEmpty() {
        List<ItemLocationCollection> out = extractor.extractLocations("item1", "cat-1","not json");
        assertThat(out).isEmpty();
    }

    @Test
    void extractLocations_parsesGeoJsonPoint() {
        String payload = "{\"catalogs\":[{\"resources\":[{\"availableAt\":[{\"geo\":{\"type\":\"Point\",\"coordinates\":[77.5946,12.9716]}}]}]}]}";
        List<ItemLocationCollection> out = extractor.extractLocations("item1", "cat-1",payload);
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
        List<ItemLocationCollection> out = extractor.extractLocations("item1", "cat-1",payload);
        assertThat(out).hasSize(2);
        // All array positions use [*] so stored paths match the discovery API's JSONPath wildcard queries.
        assertThat(out.get(0).getId().getPath()).isEqualTo("$.catalogs[*].resources[*].availableAt[*].geo");
        assertThat(out.get(1).getId().getPath()).isEqualTo("$.catalogs[*].resources[*].availableAt[*].geo");
    }

    @Test
    void extractLocations_singleGeometry_seqIsZero() {
        String payload = "{\"catalogs\":[{\"resources\":[{\"availableAt\":[{\"gps\":\"12.34,56.78\"}]}]}]}";
        List<ItemLocationCollection> out = extractor.extractLocations("item1", "cat-1", payload);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getId().getSeq()).isEqualTo((short) 0);
    }

    @Test
    void extractLocations_multipleGeometriesUnderSamePath_getDistinctSeq() {
        // #306: two geometries under the SAME wildcard path must get distinct seq so they
        // become distinct rows (PK is item_id, catalog_id, path, seq) instead of colliding.
        String payload = "{\"catalogs\":[{\"resources\":[{\"availableAt\":["
                + "{\"geo\":{\"type\":\"Point\",\"coordinates\":[77.59,12.97]}},"
                + "{\"geo\":{\"type\":\"Point\",\"coordinates\":[77.61,12.91]}}]}]}]}";
        List<ItemLocationCollection> out = extractor.extractLocations("item1", "cat-1", payload);
        assertThat(out).hasSize(2);
        // same path, distinct seq -> distinct primary keys -> both rows survive persistence
        assertThat(out).extracting(l -> l.getId().getSeq()).containsExactly((short) 0, (short) 1);
        assertThat(out).extracting(l -> l.getId().getPath()).containsOnly(
                "$.catalogs[*].resources[*].availableAt[*].geo");
    }

    @Test
    void extractLocations_distinctPaths_eachSeqStartsAtZero() {
        // Two different paths (provider gps + resource geo): seq is per-path, so each starts at 0.
        String payload = "{\"catalogs\":[{"
                + "\"provider\":{\"availableAt\":[{\"gps\":\"12.34,56.78\"}]},"
                + "\"resources\":[{\"availableAt\":[{\"geo\":{\"type\":\"Point\",\"coordinates\":[77.59,12.97]}}]}]"
                + "}]}";
        List<ItemLocationCollection> out = extractor.extractLocations("item1", "cat-1", payload);
        assertThat(out).hasSize(2);
        assertThat(out).allSatisfy(l -> assertThat(l.getId().getSeq()).isEqualTo((short) 0));
        assertThat(out).extracting(l -> l.getId().getPath()).containsExactlyInAnyOrder(
                "$.catalogs[*].provider.availableAt[*].gps",
                "$.catalogs[*].resources[*].availableAt[*].geo");
    }
}
