package org.beckn.discover.service.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.uri.URIFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
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
        URLConnection conn = uri.toURL().openConnection();
        if (conn instanceof HttpURLConnection http) {
            http.setRequestProperty("Accept", "application/json, application/yaml, text/yaml, */*");
        }
        conn.connect();

        String contentType = conn.getContentType() != null ? conn.getContentType().toLowerCase() : "";
        String uriString = uri.toString().toLowerCase();
        boolean looksLikeYaml = uriString.endsWith(".yaml") || uriString.endsWith(".yml")
                || contentType.contains("yaml");

        byte[] raw;
        try (InputStream is = conn.getInputStream()) {
            raw = is.readAllBytes();
        }

        if (!looksLikeYaml) {
            // Detect YAML by content: YAML starts with a bare key (e.g. "$id:", "---")
            // rather than a JSON object/array opener.
            String trimmed = new String(raw, StandardCharsets.UTF_8).stripLeading();
            looksLikeYaml = !trimmed.isEmpty() && trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[';
        }

        if (looksLikeYaml) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> yamlMap = yamlParser.load(new ByteArrayInputStream(raw));
                String jsonString = objectMapper.writeValueAsString(yamlMap);
                return new ByteArrayInputStream(jsonString.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                logger.error("Failed to convert YAML to JSON for URI: {}", uri, e);
                throw new IOException("Failed to convert YAML to JSON: " + e.getMessage(), e);
            }
        }

        return new ByteArrayInputStream(raw);
    }
}
