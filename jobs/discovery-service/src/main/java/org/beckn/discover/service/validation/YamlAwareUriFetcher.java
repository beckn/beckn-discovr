package org.beckn.discover.service.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.uri.URIFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Custom URI fetcher for NetworkNT that handles both JSON and YAML files.
 * Automatically converts YAML files to JSON before returning to NetworkNT,
 * since NetworkNT's default ObjectMapper only supports JSON.
 */
public class YamlAwareUriFetcher implements URIFetcher {

    private static final Logger logger = LoggerFactory.getLogger(YamlAwareUriFetcher.class);
    
    private final ObjectMapper objectMapper;
    private final Yaml yamlParser;

    public YamlAwareUriFetcher(ObjectMapper objectMapper, Yaml yamlParser) {
        this.objectMapper = objectMapper;
        this.yamlParser = yamlParser;
    }

    @Override
    public InputStream fetch(URI uri) throws IOException {
        InputStream inputStream = uri.toURL().openStream();
        
        String uriString = uri.toString().toLowerCase();
        if (uriString.endsWith(".yaml") || uriString.endsWith(".yml")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> yamlMap = yamlParser.load(inputStream);
                inputStream.close();
                
                String jsonString = objectMapper.writeValueAsString(yamlMap);
                return new ByteArrayInputStream(jsonString.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                inputStream.close();
                logger.error("Failed to convert YAML to JSON for URI: {}", uri, e);
                throw new IOException("Failed to convert YAML to JSON: " + e.getMessage(), e);
            }
        } else {
            return inputStream;
        }
    }
}
