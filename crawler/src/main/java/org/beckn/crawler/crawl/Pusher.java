package org.beckn.crawler.crawl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.crawler.config.CrawlerProperties;
import org.beckn.crawler.http.CrawlerHttpClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Step 6 (design doc §5.4 / §5.8): wrap the verified catalog part(s) in a Beckn
 * {@code catalog/publish} envelope and POST to {@code /catalog/push}.
 *
 * <p>All parts of one catalog go in a single call; no {@code publishDirectives} is sent, so the
 * publish pipeline uses its default MERGE mode and merges the parts by catalog id (OQ-3).
 * Re-pushing is safe — the pipeline upserts.
 */
@Component
public class Pusher {

    public record Result(boolean ack, int status, String detail) {}

    private final CrawlerHttpClient http;
    private final ObjectMapper mapper;
    private final String pushEndpoint;

    public Pusher(CrawlerHttpClient http, ObjectMapper mapper, CrawlerProperties props) {
        this.http = http;
        this.mapper = mapper;
        this.pushEndpoint = props.pushEndpoint();
    }

    /**
     * @param domain    provider domain (= bppId, from the manifest)
     * @param partBodies verified raw bytes of each catalog part (each a Beckn catalog document)
     */
    public Result push(String domain, List<byte[]> partBodies) throws IOException, InterruptedException {
        ObjectNode root = mapper.createObjectNode();

        ObjectNode context = root.putObject("context");
        context.put("action", "catalog/publish");
        context.put("bppId", domain);
        String bppUri = firstBppUri(partBodies);
        if (bppUri != null) context.put("bppUri", bppUri);
        context.put("messageId", UUID.randomUUID().toString());
        context.put("transactionId", UUID.randomUUID().toString());
        context.put("timestamp", Instant.now().toString());
        context.put("version", "2.0.0");

        ArrayNode catalogs = root.putObject("message").putArray("catalogs");
        for (byte[] body : partBodies) {
            catalogs.add(mapper.readTree(body)); // each part is one catalog document
        }

        String payload = mapper.writeValueAsString(root);
        CrawlerHttpClient.Response resp = http.postJson(pushEndpoint, payload);
        boolean ack = resp.status() == 200;
        String detail = "HTTP " + resp.status()
                + (resp.body() != null ? " " + new String(resp.body(), java.nio.charset.StandardCharsets.UTF_8) : "");
        return new Result(ack, resp.status(), detail);
    }

    /** bppUri comes from the catalog file itself when present (design doc §5.8); else omitted. */
    private String firstBppUri(List<byte[]> partBodies) {
        for (byte[] body : partBodies) {
            try {
                JsonNode node = mapper.readTree(body);
                JsonNode uri = node.path("bppUri");
                if (uri.isTextual() && !uri.asText().isBlank()) return uri.asText();
            } catch (IOException ignored) {
                // handled during push (readTree above); ignore here
            }
        }
        return null;
    }
}
