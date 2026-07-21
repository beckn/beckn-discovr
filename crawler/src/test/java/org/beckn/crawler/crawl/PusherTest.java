package org.beckn.crawler.crawl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.crawler.http.CrawlerHttpClient;
import org.beckn.crawler.support.TestConfigs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for the /catalog/push envelope builder (design doc §5.8). */
class PusherTest {

    private static final String ENDPOINT = "http://discovr-ingestion:8080/catalog/push";

    private CrawlerHttpClient http;
    private ObjectMapper mapper;
    private Pusher pusher;

    @BeforeEach
    void setUp() {
        http = mock(CrawlerHttpClient.class);
        mapper = new ObjectMapper();
        pusher = new Pusher(http, mapper, TestConfigs.props(ENDPOINT));
    }

    private byte[] catalogPart(String id, String bppUri) {
        return ("{\"id\":\"" + id + "\",\"bppUri\":\"" + bppUri + "\","
                + "\"descriptor\":{\"name\":\"Test\"}}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void buildsSpecCompliantEnvelope() throws Exception {
        when(http.postJson(eq(ENDPOINT), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new CrawlerHttpClient.Response(200, "{\"message\":{\"status\":\"ACK\"}}".getBytes(), null));

        Pusher.Result result = pusher.push("prov.example",
                List.of(catalogPart("CAT-1", "https://prov.example/bpp")));

        assertThat(result.ack()).isTrue();
        assertThat(result.status()).isEqualTo(200);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(http).postJson(eq(ENDPOINT), body.capture());
        JsonNode root = mapper.readTree(body.getValue());

        JsonNode ctx = root.get("context");
        assertThat(ctx.get("action").asText()).isEqualTo("catalog/publish");
        assertThat(ctx.get("bppId").asText()).isEqualTo("prov.example");
        assertThat(ctx.get("bppUri").asText()).isEqualTo("https://prov.example/bpp");
        assertThat(ctx.get("version").asText()).isEqualTo("2.0.0");
        // messageId / transactionId are fresh UUIDs
        assertThatCode(() -> UUID.fromString(ctx.get("messageId").asText())).doesNotThrowAnyException();
        assertThatCode(() -> UUID.fromString(ctx.get("transactionId").asText())).doesNotThrowAnyException();
        assertThat(ctx.get("timestamp").asText()).isNotBlank();

        // No publishDirectives → publish pipeline defaults to MERGE (OQ-3)
        assertThat(root.has("publishDirectives")).isFalse();
        assertThat(ctx.has("publishDirectives")).isFalse();

        JsonNode catalogs = root.get("message").get("catalogs");
        assertThat(catalogs.isArray()).isTrue();
        assertThat(catalogs).hasSize(1);
        assertThat(catalogs.get(0).get("id").asText()).isEqualTo("CAT-1");
    }

    @Test
    void multiplePartsBecomeMultipleCatalogsInOneCall() throws Exception {
        when(http.postJson(eq(ENDPOINT), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new CrawlerHttpClient.Response(200, new byte[0], null));

        pusher.push("prov.example", List.of(
                catalogPart("CAT-1", "https://prov.example/bpp"),
                catalogPart("CAT-2", "https://prov.example/bpp")));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(http).postJson(eq(ENDPOINT), body.capture());
        JsonNode catalogs = mapper.readTree(body.getValue()).get("message").get("catalogs");
        assertThat(catalogs).hasSize(2);
        assertThat(catalogs.get(0).get("id").asText()).isEqualTo("CAT-1");
        assertThat(catalogs.get(1).get("id").asText()).isEqualTo("CAT-2");
    }

    @Test
    void nonPublishBppUri_omittedWhenAbsentFromCatalog() throws Exception {
        when(http.postJson(eq(ENDPOINT), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new CrawlerHttpClient.Response(200, new byte[0], null));
        byte[] noUri = "{\"id\":\"CAT-1\",\"descriptor\":{\"name\":\"NoUri\"}}".getBytes(StandardCharsets.UTF_8);

        pusher.push("prov.example", List.of(noUri));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(http).postJson(eq(ENDPOINT), body.capture());
        assertThat(mapper.readTree(body.getValue()).get("context").has("bppUri")).isFalse();
    }

    @Test
    void nonAckStatus_reportedNotAck() throws Exception {
        when(http.postJson(eq(ENDPOINT), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new CrawlerHttpClient.Response(400, "bad".getBytes(), null));

        Pusher.Result result = pusher.push("prov.example",
                List.of(catalogPart("CAT-1", "https://prov.example/bpp")));

        assertThat(result.ack()).isFalse();
        assertThat(result.status()).isEqualTo(400);
        assertThat(result.detail()).contains("400");
    }
}
