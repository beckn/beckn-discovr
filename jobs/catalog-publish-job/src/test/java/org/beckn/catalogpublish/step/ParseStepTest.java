package org.beckn.catalogpublish.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.exception.PayloadParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParseStepTest {

    private ParseStep parseStep;

    @BeforeEach
    void setUp() {
        parseStep = new ParseStep(new ObjectMapper());
    }

    @Test
    void parse_throwsOnInvalidJson() {
        assertThatThrownBy(() -> parseStep.parse("not json"))
                .isInstanceOf(PayloadParseException.class);
    }

    @Test
    void parse_throwsWhenNoCatalogs() {
        String msg = "{\"context\":{\"networkId\":\"net-1\"},\"message\":{\"catalogs\":[]}}";
        assertThatThrownBy(() -> parseStep.parse(msg))
                .isInstanceOf(PayloadParseException.class)
                .hasMessageContaining("No catalogs");
    }

    @Test
    void parse_extractsContextAndCatalogs() {
        String msg = """
                {"context":{"networkId":"net-1","subscriberId":"sub-1"},"message":{"catalogs":[{"id":"c1","resources":[]}]}}
                """;
        var parsed = parseStep.parse(msg);
        assertThat(parsed.context().networkIds()).containsExactly("net-1");
        assertThat(parsed.catalogs()).hasSize(1);
        assertThat(parsed.catalogs().get(0).path("id").asText()).isEqualTo("c1");
    }

    @Test
    void extractCatalogIdSafe_returnsUnknownWhenMissing() {
        var node = new ObjectMapper().createObjectNode();
        assertThat(parseStep.extractCatalogIdSafe(node)).isNull();
    }
}
