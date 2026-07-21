package org.beckn.crawler.http;

import org.beckn.crawler.config.CrawlerProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin GET/POST wrapper over the JDK HttpClient. Enforces a per-request timeout and a byte
 * cap on fetched bodies (design doc §5.1 / §5.4). No caching header logic in the POC — the
 * digest chain is the authoritative change signal (§5.6).
 */
@Component
public class CrawlerHttpClient {

    /** A fetched body plus its ETag (may be null — hosts like the ngrok node send none). */
    public record Response(int status, byte[] body, String etag) {}

    private final HttpClient client;
    private final Duration timeout;
    private final long maxBytes;

    public CrawlerHttpClient(CrawlerProperties props) {
        this.timeout = props.http().timeout();
        this.maxBytes = props.http().maxPartBytes();
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** GET the URL as raw bytes. Rejects a body larger than the configured cap. */
    public Response get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = resp.body();
        if (body != null && body.length > maxBytes) {
            throw new IOException("response body " + body.length + " bytes exceeds cap " + maxBytes + " for " + url);
        }
        String etag = resp.headers().firstValue("ETag").orElse(null);
        return new Response(resp.statusCode(), body, etag);
    }

    /** POST a JSON body; returns status + response bytes (used for /catalog/push). */
    public Response postJson(String url, String jsonBody) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        return new Response(resp.statusCode(), resp.body(), null);
    }
}
