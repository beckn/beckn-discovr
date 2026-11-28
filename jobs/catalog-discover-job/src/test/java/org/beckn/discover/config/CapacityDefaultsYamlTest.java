package org.beckn.discover.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts application.yml capacity defaults for the 100M p99 workstream.
 */
class CapacityDefaultsYamlTest {

    @Test
    void discoverApplicationYamlRaisesListenerConcurrencyAndRegistryCache() throws Exception {
        Path yml = resolve("src/main/resources/application.yml");
        try (InputStream in = Files.newInputStream(yml)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = new Yaml().load(in);

            @SuppressWarnings("unchecked")
            Map<String, Object> spring = (Map<String, Object>) root.get("spring");
            @SuppressWarnings("unchecked")
            Map<String, Object> kafka = (Map<String, Object>) spring.get("kafka");
            @SuppressWarnings("unchecked")
            Map<String, Object> listener = (Map<String, Object>) kafka.get("listener");
            assertThat(String.valueOf(listener.get("concurrency")))
                    .isEqualTo("${SPRING_KAFKA_LISTENER_CONCURRENCY:8}");

            @SuppressWarnings("unchecked")
            Map<String, Object> discovery = (Map<String, Object>) root.get("discovery");
            @SuppressWarnings("unchecked")
            Map<String, Object> auth = (Map<String, Object>) discovery.get("auth");
            assertThat(String.valueOf(auth.get("cache-max-keys")))
                    .isEqualTo("${SIGNATURE_CACHE_MAX_KEYS:10000}");

            @SuppressWarnings("unchecked")
            Map<String, Object> postgresql = (Map<String, Object>) discovery.get("postgresql");
            assertThat(String.valueOf(postgresql.get("parallel-query-workers")))
                    .isEqualTo("${DISCOVERY_POSTGRESQL_PARALLEL_QUERY_WORKERS:16}");
        }
    }

    private static Path resolve(String relative) {
        Path direct = Path.of(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        return Path.of("jobs/catalog-discover-job").resolve(relative);
    }
}
