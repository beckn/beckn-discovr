package org.beckn.discover.service.validation;

import org.beckn.discover.config.DiscoveryProperties;
import org.beckn.discover.config.YamlConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the actual bug behind issue #474: {@link DiscoveryValidationService#init()} used to
 * perform a network fetch of {@code beckn.yaml} at {@code @PostConstruct} time unconditionally,
 * so an unreachable/bad schema URL made the whole Spring context hang/fail to start — even
 * though schema validation is redundant in production (Onix already validates upstream).
 *
 * <p>Uses a narrow {@link ApplicationContextRunner} scoped to just the beans
 * {@link DiscoveryValidationService} needs, so the assertion is fast and doesn't require
 * Kafka/PostgreSQL/Testcontainers infrastructure.</p>
 */
class SchemaValidationToggleContextTest {

    private static final String UNREACHABLE_SCHEMA_URL =
            "http://schema-host-that-does-not-exist.invalid:1/beckn.yaml";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    RestClientAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class, YamlConfig.class,
                    SchemaLoaderService.class, DiscoveryValidationService.class);

    /**
     * Required-but-defaultless {@code @NotBlank} fields elsewhere on {@link DiscoveryProperties}
     * (kafka topics, nlweb base-url) so {@code @EnableConfigurationProperties} binding/validation
     * succeeds — irrelevant to this test's schema-toggle behavior but otherwise fails bean
     * creation before {@link DiscoveryValidationService#init()} even runs.
     */
    private static final String[] REQUIRED_UNRELATED_PROPERTIES = {
            "discovery.kafka.request-topic=catalog.discovery.request",
            "discovery.kafka.response-topic=catalog.discovery.response",
            "discovery.nlweb.base-url=http://localhost:8000",
            "discovery.nlweb.ask-endpoint=/ask"
    };

    @Test
    void contextStartsFast_whenValidationDisabled_evenWithUnreachableSchemaUrl() {
        contextRunner
                .withPropertyValues(REQUIRED_UNRELATED_PROPERTIES)
                .withPropertyValues(
                        "discovery.schema.validation-enabled=false",
                        "discovery.schema.url=" + UNREACHABLE_SCHEMA_URL,
                        "discovery.schema.fetch-timeout-seconds=1")
                .run((AssertableApplicationContext context) -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DiscoveryValidationService.class);
                });
    }

    @Test
    void contextFailsToStart_whenValidationEnabled_andSchemaUrlUnreachable_regressionGuard() {
        contextRunner
                .withPropertyValues(REQUIRED_UNRELATED_PROPERTIES)
                .withPropertyValues(
                        "discovery.schema.validation-enabled=true",
                        "discovery.schema.url=" + UNREACHABLE_SCHEMA_URL,
                        "discovery.schema.fetch-timeout-seconds=1")
                .run((AssertableApplicationContext context) -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().isNotNull();
                });
    }

    @Configuration
    @EnableConfigurationProperties(DiscoveryProperties.class)
    static class TestConfig {
    }
}
