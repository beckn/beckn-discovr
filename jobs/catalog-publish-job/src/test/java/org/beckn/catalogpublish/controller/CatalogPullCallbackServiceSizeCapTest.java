package org.beckn.catalogpublish.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.metrics.CatalogPublishMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Verifies the on_pull download path hard-caps decompressed output so a gzip stream that expands
 * beyond a small configured cap is ABORTED and surfaces as the distinct {@code size_exceeded}
 * failure reason (never an OOM), with NO double-count against {@code decompress_error} /
 * {@code processing_error} and NO enqueue to the publish pipeline.
 *
 * <p>The private {@code decompressGzipPayload} is exercised through the public async entrypoint: a
 * loopback {@link HttpServer} serves the compressed bytes and the SSRF guard is disabled so the
 * download reaches the decompress step. No Docker required.</p>
 */
class CatalogPullCallbackServiceSizeCapTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private byte[] servedBytes;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/catalog.json.gz", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/gzip");
            exchange.sendResponseHeaders(200, servedBytes.length);
            exchange.getResponseBody().write(servedBytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void decompressedOutputBeyondCap_abortsAsSizeExceeded_notDecompressError_noEnqueue() throws Exception {
        // A ~1 MiB payload that gzips to a tiny blob — expands FAR beyond the 1 KiB decompressed cap.
        byte[] plain = new byte[1_048_576]; // zeros compress to almost nothing
        servedBytes = gzip(plain);

        // Cap the download high enough that the compressed bytes pass, but cap decompressed at 1 KiB
        // so decompression aborts mid-stream.
        AppProperties.Catalog catalog = new AppProperties.Catalog(
                10_000_000L, false,
                "https://raw.githubusercontent.com/beckn/protocol-specifications-v2/refs/heads/main/api/v2.0.0/beckn.yaml",
                1, 4, null, null, null,
                /* pullSsrfCheckEnabled */ false, // loopback download must be allowed in-test
                /* pullMaxDownloadBytes */ 10_000_000L,
                /* pullMaxDecompressedBytes */ 1_024L);

        CatalogPushService push = Mockito.mock(CatalogPushService.class);
        CatalogPublishMetrics metrics = Mockito.mock(CatalogPublishMetrics.class);
        CatalogPullCallbackService service = new CatalogPullCallbackService(
                push, mapper, metrics, new AppProperties(null, null, catalog));

        String url = "http://" + server.getAddress().getHostString()
                + ":" + server.getAddress().getPort() + "/catalog.json.gz";
        String checksum = sha256Hex(servedBytes); // checksum of the compressed bytes AT url (must pass)
        String expiresAt = OffsetDateTime.now().plusHours(1).toString();

        String payload = """
                {
                  "context": {"messageId":"m1","transactionId":"t1"},
                  "message": {
                    "status": "COMPLETED",
                    "downloadManifest": {
                      "url": "%s",
                      "format": "json.gz",
                      "checksum": "%s",
                      "expiresAt": "%s"
                    }
                  }
                }
                """.formatted(url, checksum, expiresAt);

        // Runs inline (no Spring proxy). Must NOT OOM; must classify as size_exceeded.
        service.processPullCallbackAsynchronously(payload);

        verify(metrics).recordOnPullFailed("size_exceeded");
        // No double-count against the other on_pull failure reasons for this same event.
        verify(metrics, never()).recordOnPullFailed("decompress_error");
        verify(metrics, never()).recordOnPullFailed("processing_error");
        verify(metrics, never()).recordOnPullFailed("download_http_error");
        // Oversized payload is discarded — nothing reaches the publish pipeline.
        verify(push, never()).enqueueForProcessing(Mockito.anyString());
    }

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(data);
        }
        return baos.toByteArray();
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }
}
