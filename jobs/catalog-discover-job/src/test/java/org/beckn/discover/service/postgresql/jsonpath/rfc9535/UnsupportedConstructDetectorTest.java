package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.snack4.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.beckn.discover.service.postgresql.jsonpath.rfc9535.UnsupportedConstructException.UnsupportedConstruct;

class UnsupportedConstructDetectorTest {

    private final UnsupportedConstructDetector detector = new UnsupportedConstructDetector();

    @Test
    @DisplayName("descendant segment ('..') is flagged")
    void descendantSegment_flagged() {
        assertThat(detect("$..offers[?(@.price<1)]")).contains(UnsupportedConstruct.DESCENDANT_SEGMENT);
    }

    @Test
    @DisplayName("slice with step is flagged")
    void sliceWithStep_flagged() {
        assertThat(detect("$.offers[0:5:2]")).contains(UnsupportedConstruct.SLICE_WITH_STEP);
    }

    @Test
    @DisplayName("count() function is flagged")
    void countFunction_flagged() {
        assertThat(detect("$.offers.count()")).contains(UnsupportedConstruct.COUNT_FUNCTION);
    }

    @Test
    @DisplayName("value() function is flagged")
    void valueFunction_flagged() {
        assertThat(detect("$.offers[0].value()")).contains(UnsupportedConstruct.VALUE_FUNCTION);
    }

    @Test
    @DisplayName("match()/search() functions are flagged as regex-unsupported")
    void regexFunctions_flagged() {
        assertThat(detect("$.offers.match()")).contains(UnsupportedConstruct.REGEX_FUNCTION);
        assertThat(detect("$.offers.search()")).contains(UnsupportedConstruct.REGEX_FUNCTION);
    }

    @Test
    @DisplayName("supported expressions are not flagged")
    void supportedExpressions_notFlagged() {
        assertThat(detect("$.offers[?(@.price < 100)]")).isEmpty();
        assertThat(detect("$.resources[*]")).isEmpty();
        assertThat(detect("$.offers[0]")).isEmpty();
        assertThat(detect("$.offers[1:3]")).isEmpty();
    }

    private java.util.Optional<UnsupportedConstruct> detect(String expression) {
        return detector.firstUnsupported(JsonPath.parse(expression).getSegments());
    }
}
