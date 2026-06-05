package org.beckn.discover.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DiscoverRequest.SpatialConstraint}.
 *
 * <p>The Beckn spec defines the {@code op} enum in UPPERCASE (S_DWITHIN, S_WITHIN, …)
 * but Discovr's spatial engines compare against lowercase keys. The setter
 * normalizes incoming values to lowercase so spec-compliant uppercase requests
 * are accepted instead of silently degrading to a point-intersect query.</p>
 */
class SpatialConstraintTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void setOperation_lowercaseInput_storedAsLowercase() {
        DiscoverRequest.SpatialConstraint c = new DiscoverRequest.SpatialConstraint();
        c.setOperation("s_dwithin");
        assertThat(c.getOperation()).isEqualTo("s_dwithin");
    }

    @Test
    void setOperation_uppercaseInputPerSpec_normalizedToLowercase() {
        // Regression: spec-compliant UPPERCASE "S_DWITHIN" used to silently bypass
        // the EsSpatialQueryBuilder dwithin branch (which compares "s_dwithin".equals(op))
        // and degrade to a Point-intersect query with distanceMeters ignored.
        DiscoverRequest.SpatialConstraint c = new DiscoverRequest.SpatialConstraint();
        c.setOperation("S_DWITHIN");
        assertThat(c.getOperation()).isEqualTo("s_dwithin");
    }

    @Test
    void setOperation_mixedCaseInput_normalizedToLowercase() {
        DiscoverRequest.SpatialConstraint c = new DiscoverRequest.SpatialConstraint();
        c.setOperation("S_dWithin");
        assertThat(c.getOperation()).isEqualTo("s_dwithin");
    }

    @Test
    void setOperation_nullInput_storedAsNull() {
        DiscoverRequest.SpatialConstraint c = new DiscoverRequest.SpatialConstraint();
        c.setOperation(null);
        assertThat(c.getOperation()).isNull();
    }

    @Test
    void jsonDeserialization_uppercaseOp_normalizedDuringParse() throws Exception {
        // Jackson invokes setOperation() during deserialization, so spec-uppercase
        // values in the wire payload are normalized at parse time.
        String json = "{\"op\":\"S_DWITHIN\",\"targets\":\"$.x.geo\",\"distanceMeters\":1000}";
        DiscoverRequest.SpatialConstraint c =
                objectMapper.readValue(json, DiscoverRequest.SpatialConstraint.class);
        assertThat(c.getOperation()).isEqualTo("s_dwithin");
        assertThat(c.getDistanceMeters()).isEqualTo(1000.0);
    }
}
