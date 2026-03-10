package org.beckn.discover.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;

/**
 * YAML Parser Configuration
 * 
 * Provides a shared YAML parser bean used across services that parse YAML content.
 * This ensures efficient resource usage and consistent YAML parsing behavior.
 */
@Configuration
public class YamlConfig {

    /**
     * Creates a shared YAML parser instance.
     * 
     * @return Configured YAML parser
     */
    @Bean
    public Yaml yamlParser() {
        return new Yaml();
    }
}
