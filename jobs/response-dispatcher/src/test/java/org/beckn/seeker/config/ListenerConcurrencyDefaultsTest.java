package org.beckn.seeker.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ListenerConcurrencyDefaultsTest {

    @Test
    void applicationYamlDefaultConcurrencyIsGreaterThanOne() throws Exception {
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
                    .isEqualTo("${SPRING_KAFKA_LISTENER_CONCURRENCY:4}");
        }
    }

    private static Path resolve(String relative) {
        Path direct = Path.of(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        return Path.of("jobs/response-dispatcher").resolve(relative);
    }
}
