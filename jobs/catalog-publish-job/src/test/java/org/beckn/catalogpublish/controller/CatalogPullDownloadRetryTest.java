package org.beckn.catalogpublish.controller;

import com.sun.net.httpserver.HttpServer;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.metrics.CatalogPublishMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.retry.annotation.EnableRetry;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Verifies the bounded {@link org.springframework.retry.annotation.Retryable} retry on the on_pull
 * download for TRANSIENT failures (HTTP 5xx / network), so a fire-and-forget pull result isn't lost on
 * a blip — while PERMANENT failures (4xx) are not retried. Because {@code @Retryable} works only via
 * the Spring proxy, the downloader is driven through a minimal {@link EnableRetry} Spring context
 * against a loopback {@link HttpServer} (SSRF disabled, small backoff). No Docker required.
 */
class CatalogPullDownloadRetryTest {

    private static final byte[] BODY = "catalog-bytes".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();

    private AnnotationConfigApplicationContext ctx;
    private CatalogPublishMetrics metrics;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        if (ctx != null) ctx.close();
    }

    /** Serves `failStatus` for the first `failTimes` requests, then 200 with BODY. */
    private String startServer(int failTimes, int failStatus) throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/catalog.json.gz", exchange -> {
            int n = calls.incrementAndGet();
            if (n <= failTimes) {
                exchange.sendResponseHeaders(failStatus, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/gzip");
            exchange.sendResponseHeaders(200, BODY.length);
            exchange.getResponseBody().write(BODY);
            exchange.close();
        });
        server.start();
        return "http://" + server.getAddress().getHostString()
                + ":" + server.getAddress().getPort() + "/catalog.json.gz";
    }

    /**
     * Builds a proxied {@link SecureCatalogDownloader} inside an {@code @EnableRetry} context so the
     * {@code @Retryable}/{@code @Recover} annotations are honored (max {@code maxAttempts} attempts,
     * tiny 5ms backoff). SSRF disabled so a loopback download is allowed in-test.
     */
    private SecureCatalogDownloader downloader(int maxAttempts) {
        metrics = Mockito.mock(CatalogPublishMetrics.class);
        var catalog = new AppProperties.Catalog(
                10_000_000L, false,
                "https://raw.githubusercontent.com/beckn/protocol-specifications-v2/refs/heads/main/api/v2.0.0/beckn.yaml",
                1, 4, null, null, null,
                /* pullSsrfCheckEnabled */ false,
                /* pullMaxDownloadBytes */ 10_000_000L,
                /* pullMaxDecompressedBytes */ 10_000_000L,
                /* pullDownloadMaxAttempts */ maxAttempts,
                /* pullDownloadRetryBackoffMs */ 5L,
                /* pullDnsCacheTtlSeconds */ null);
        AppProperties props = new AppProperties(null, null, catalog);

        ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(CatalogPublishMetrics.class, () -> metrics);
        ctx.registerBean(AppProperties.class, () -> props);
        ctx.registerBean(SecureCatalogDownloader.class);
        ctx.register(RetryConfig.class);
        ctx.refresh();
        return ctx.getBean(SecureCatalogDownloader.class);
    }

    @EnableRetry
    static class RetryConfig {
    }

    @Test
    void transient5xxThenSuccess_retriesAndReturnsBody() throws Exception {
        String url = startServer(2, 503);   // 503, 503, then 200
        var dl = downloader(3);

        byte[] result = dl.download(url);

        assertThat(result).isEqualTo(BODY);
        assertThat(calls.get()).isEqualTo(3);          // 2 failures + 1 success
        verify(metrics, times(2)).recordOnPullDownloadRetry();
    }

    @Test
    void clientError4xx_notRetried() throws Exception {
        String url = startServer(99, 404);  // always 404
        var dl = downloader(3);

        assertThatThrownBy(() -> dl.download(url))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("404");
        assertThat(calls.get()).isEqualTo(1);          // 4xx is permanent → no retry
        verify(metrics, never()).recordOnPullDownloadRetry();
    }

    @Test
    void persistent5xx_exhaustsRetriesThenThrows() throws Exception {
        String url = startServer(99, 503);  // always 503
        var dl = downloader(3);

        assertThatThrownBy(() -> dl.download(url))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("503");
        assertThat(calls.get()).isEqualTo(3);          // maxAttempts total
        verify(metrics, times(2)).recordOnPullDownloadRetry(); // retries between the 3 attempts
    }
}
