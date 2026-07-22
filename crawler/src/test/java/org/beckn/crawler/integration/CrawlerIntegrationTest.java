package org.beckn.crawler.integration;

import com.sun.net.httpserver.HttpServer;
import org.beckn.crawler.crawl.Crawler;
import org.beckn.crawler.state.StateStore;
import org.beckn.crawler.util.DigestUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end crawl pass against a real Postgres (Testcontainers) and an in-process mock bucket
 * that serves the DeDi digest chain (manifest → index → catalog part) and receives the push.
 *
 * <p>Proves the three POC scenarios plus the integrity guard:
 * <ul>
 *   <li>fresh catalog → exactly one push, state recorded</li>
 *   <li>unchanged catalog → zero pushes (cheap top-level digest skip)</li>
 *   <li>modified catalog → one more push</li>
 *   <li>tampered part (digest mismatch) → rejected, not pushed, state NOT advanced (retries next pass)</li>
 * </ul>
 *
 * <p>Requires Docker. If Docker is unavailable locally this runs in CI (see build.gradle
 * {@code integrationTest}).
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class CrawlerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static MockBucket bucket;

    @BeforeAll
    static void startBucket() throws IOException {
        bucket = new MockBucket();
        bucket.start();
        bucket.publishFresh(); // scenario 1 setup: one public ACTIVE catalog
    }

    @AfterAll
    static void stopBucket() {
        if (bucket != null) bucket.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("CRAWLER_DB_URL", POSTGRES::getJdbcUrl);
        reg.add("CRAWLER_DB_USERNAME", POSTGRES::getUsername);
        reg.add("CRAWLER_DB_PASSWORD", POSTGRES::getPassword);
        reg.add("CRAWLER_PROVIDERS", () -> bucket.baseUrl() + "/.well-known/dedi.json");
        reg.add("CRAWLER_PUSH_ENDPOINT", () -> bucket.baseUrl() + "/catalog/push");
        reg.add("CRAWLER_MANIFEST_REFRESH_INTERVAL", () -> "7d");  // scheduler is off anyway
        reg.add("CRAWLER_INDEX_POLL_INTERVAL", () -> "1m");        // scheduler is off anyway
        reg.add("CRAWLER_FEEDBACK_LOG_PATH", () -> "build/it-feedback.log");
        reg.add("crawler.scheduler.enabled", () -> "false");      // drive passes manually
    }

    @Autowired
    Crawler crawler;
    @Autowired
    StateStore state;

    @Test
    void fullLifecycle_fresh_unchanged_modified_tampered() {
        String partUrl = bucket.baseUrl() + "/catalogs/CAT-1.json";

        // ── Scenario 1: fresh catalog → one push, state recorded ──────────────
        crawler.runIndexPass();
        assertThat(bucket.pushCount()).as("fresh → 1 push").isEqualTo(1);
        Optional<StateStore.PartState> after1 = state.findPart(partUrl);
        assertThat(after1).isPresent();
        assertThat(after1.get().catalogId()).isEqualTo("CAT-1");
        assertThat(after1.get().version()).isEqualTo(1L);
        String digestAfter1 = after1.get().digest();

        // ── Scenario 2: unchanged → zero additional pushes ────────────────────
        crawler.runIndexPass();
        assertThat(bucket.pushCount()).as("unchanged → still 1 push").isEqualTo(1);

        // ── Scenario 3: modified → one more push, new digest/version stored ────
        bucket.modify(); // new content, version 2
        crawler.runIndexPass();
        assertThat(bucket.pushCount()).as("modified → 2 pushes").isEqualTo(2);
        StateStore.PartState after3 = state.findPart(partUrl).orElseThrow();
        assertThat(after3.version()).isEqualTo(2L);
        assertThat(after3.digest()).isNotEqualTo(digestAfter1);

        // ── Integrity guard: tampered part (announced digest ≠ served bytes) ──
        bucket.corruptNextPart(); // version 3 bytes, but index announces a bogus digest
        crawler.runIndexPass();
        assertThat(bucket.pushCount()).as("tampered → NOT pushed").isEqualTo(2);
        // state must not advance — the good version 2 is preserved so it retries next pass
        assertThat(state.findPart(partUrl).orElseThrow().version()).isEqualTo(2L);
    }

    // ── In-process DeDi bucket + push sink ───────────────────────────────────

    static final class MockBucket {
        private HttpServer server;
        private int port;
        private final AtomicInteger pushes = new AtomicInteger();

        private volatile long version;
        private volatile String partBody;
        private volatile String partDigestOverride; // null = announce the true digest

        private volatile byte[] manifestBytes;
        private volatile byte[] indexBytes;
        private volatile byte[] partBytes;

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = server.getAddress().getPort();
            server.createContext("/.well-known/dedi.json", ex -> respond(ex, 200, manifestBytes));
            server.createContext("/dedi/index.json", ex -> respond(ex, 200, indexBytes));
            server.createContext("/catalogs/CAT-1.json", ex -> respond(ex, 200, partBytes));
            server.createContext("/catalog/push", ex -> {
                ex.getRequestBody().readAllBytes(); // drain
                pushes.incrementAndGet();
                respond(ex, 200, "{\"message\":{\"status\":\"ACK\"}}".getBytes(StandardCharsets.UTF_8));
            });
            server.start();
        }

        void stop() { server.stop(0); }

        String baseUrl() { return "http://127.0.0.1:" + port; }

        int pushCount() { return pushes.get(); }

        void publishFresh() {
            version = 1;
            partBody = catalog("Fresh Store");
            partDigestOverride = null;
            rebuild();
        }

        void modify() {
            version++;
            partBody = catalog("Renamed Store");
            partDigestOverride = null;
            rebuild();
        }

        void corruptNextPart() {
            version++;
            partBody = catalog("Tampered Store");
            partDigestOverride = "sha-256:0000000000000000000000000000000000000000000000000000000000000000";
            rebuild();
        }

        private String catalog(String name) {
            return "{\"id\":\"CAT-1\",\"bppUri\":\"" + baseUrl() + "/bpp\","
                    + "\"descriptor\":{\"name\":\"" + name + "\"}}";
        }

        /** Recompute the digest chain bottom-up so manifest→index→part stays self-consistent. */
        private void rebuild() {
            partBytes = partBody.getBytes(StandardCharsets.UTF_8);
            String truePartDigest = DigestUtil.sha256(partBytes);
            String announcedPartDigest = partDigestOverride != null ? partDigestOverride : truePartDigest;

            String index = "{"
                    + "\"type\":\"dedi-file\","
                    + "\"publisher\":{\"domain\":\"prov.example\"},"
                    + "\"namespace\":\"beckn-catalogs\","
                    + "\"next_update\":\"2026-07-21T00:00:00Z\","
                    + "\"records\":[{"
                    + "\"record_name\":\"CAT-1\","
                    + "\"details\":{"
                    + "\"catalogId\":\"CAT-1\",\"version\":" + version + ","
                    + "\"catalogType\":\"generic\",\"status\":\"ACTIVE\",\"visibility\":\"public\","
                    + "\"updatedAt\":\"2026-07-21T00:00:00Z\","
                    + "\"parts\":[{\"url\":\"" + baseUrl() + "/catalogs/CAT-1.json\","
                    + "\"digest\":\"" + announcedPartDigest + "\","
                    + "\"lastModified\":\"2026-07-21T00:00:00Z\"}]"
                    + "}}]}";
            indexBytes = index.getBytes(StandardCharsets.UTF_8);
            String indexDigest = DigestUtil.sha256(indexBytes);

            String manifest = "{"
                    + "\"type\":\"dedi-manifest\",\"domain\":\"prov.example\","
                    + "\"files\":[{\"registry\":\"beckn-catalogs\","
                    + "\"url\":\"" + baseUrl() + "/dedi/index.json\","
                    + "\"digest\":\"" + indexDigest + "\",\"state\":\"live\"}]}";
            manifestBytes = manifest.getBytes(StandardCharsets.UTF_8);
        }

        private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, byte[] body)
                throws IOException {
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }
}
