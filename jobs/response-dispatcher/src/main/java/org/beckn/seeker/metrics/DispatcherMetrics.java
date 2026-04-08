package org.beckn.seeker.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Micrometer metrics for the Response Dispatcher.
 *
 * <ul>
 *   <li>{@code dispatcher.messages.total} — callback delivery count, tagged by status (success|failure)</li>
 *   <li>{@code dispatcher.callback.duration} — HTTP callback latency timer, tagged by outcome</li>
 * </ul>
 *
 * Kafka consumer lag is collected automatically by Micrometer's built-in
 * {@code kafka.consumer.fetch.manager.records.lag} gauge when both
 * spring-boot-starter-actuator and spring-kafka are present.
 */
@Component
public class DispatcherMetrics {

    private static final String METRIC_MESSAGES_TOTAL    = "discovr.dispatcher.messages.total";
    private static final String METRIC_CALLBACK_DURATION = "discovr.dispatcher.callback.duration";

    private static final String TAG_STATUS       = "status";
    private static final String TAG_OUTCOME      = "outcome";
    private static final String STATUS_SUCCESS   = "success";
    private static final String STATUS_FAILURE   = "failure";
    private static final String OUTCOME_SUCCESS  = "success";
    private static final String OUTCOME_CLIENT_ERROR = "client_error";
    private static final String OUTCOME_FAILURE  = "failure";

    private final Counter successCounter;
    private final Counter failureCounter;

    private final Timer callbackTimerSuccess;
    private final Timer callbackTimerClientError;
    private final Timer callbackTimerFailure;

    public DispatcherMetrics(MeterRegistry registry) {
        this.successCounter = Counter.builder(METRIC_MESSAGES_TOTAL)
                .description("Number of callback deliveries, tagged by outcome")
                .tag(TAG_STATUS, STATUS_SUCCESS)
                .register(registry);

        this.failureCounter = Counter.builder(METRIC_MESSAGES_TOTAL)
                .description("Number of callback deliveries, tagged by outcome")
                .tag(TAG_STATUS, STATUS_FAILURE)
                .register(registry);

        this.callbackTimerSuccess = Timer.builder(METRIC_CALLBACK_DURATION)
                .description("HTTP callback latency to BAP/BPP endpoints")
                .tag(TAG_OUTCOME, OUTCOME_SUCCESS)
                .register(registry);

        this.callbackTimerClientError = Timer.builder(METRIC_CALLBACK_DURATION)
                .description("HTTP callback latency to BAP/BPP endpoints")
                .tag(TAG_OUTCOME, OUTCOME_CLIENT_ERROR)
                .register(registry);

        this.callbackTimerFailure = Timer.builder(METRIC_CALLBACK_DURATION)
                .description("HTTP callback latency to BAP/BPP endpoints")
                .tag(TAG_OUTCOME, OUTCOME_FAILURE)
                .register(registry);
    }

    /** Increments the success delivery counter. */
    public void incrementSuccess() {
        successCounter.increment();
    }

    /** Increments the failure delivery counter. */
    public void incrementFailure() {
        failureCounter.increment();
    }

    /** Returns the pre-registered callback timer for the {@code success} outcome. */
    public Timer callbackTimerSuccess() {
        return callbackTimerSuccess;
    }

    /** Returns the pre-registered callback timer for the {@code client_error} outcome. */
    public Timer callbackTimerClientError() {
        return callbackTimerClientError;
    }

    /** Returns the pre-registered callback timer for the {@code failure} outcome. */
    public Timer callbackTimerFailure() {
        return callbackTimerFailure;
    }
}
