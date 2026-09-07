package org.beckn.discover.service.postgresql.jsonpath.rfc9535;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Migration-visibility counter: {@code discovr.filter.grammar{path=rfc9535|legacy|rejected}}.
 * No client-visible tag — metrics/logs only (see design doc "Metrics / logging").
 */
@Component
public class FilterGrammarMetrics {

    private static final String METRIC_NAME = "discovr.filter.grammar";

    private final MeterRegistry meterRegistry;

    public FilterGrammarMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordRfc9535() {
        meterRegistry.counter(METRIC_NAME, "path", "rfc9535").increment();
    }

    public void recordLegacy() {
        meterRegistry.counter(METRIC_NAME, "path", "legacy").increment();
    }

    public void recordRejected(String reason) {
        meterRegistry.counter(METRIC_NAME, "path", "rejected", "reason", reason).increment();
    }
}
