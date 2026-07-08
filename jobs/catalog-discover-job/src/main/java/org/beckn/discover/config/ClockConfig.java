package org.beckn.discover.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides the application {@link Clock} used as the "now" reference when evaluating
 * catalog {@code validity} windows for the opt-in {@code activeOnly} discover filter.
 *
 * <p>Injected (rather than calling {@code Instant.now()} inline) so tests can substitute a
 * fixed clock and assert deterministic active/expired boundaries.</p>
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
