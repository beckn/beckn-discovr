package org.beckn.seeker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.beckn.auth.BecknAuth;
import org.beckn.auth.verification.RegistryEntry;
import org.beckn.seeker.config.HttpClientProperties;
import org.beckn.seeker.config.SigningProperties;
import org.beckn.seeker.config.StaticCallbackProperties;
import org.beckn.seeker.metrics.DispatcherMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@code HttpService.resolveTargetUrl} covering the DeDi → context.bapUri
 * fallback behaviour and the static-callback stamping rules.
 *
 * <p>{@code resolveTargetUrl} is private; tests invoke it via reflection so we can isolate
 * URL resolution without exercising the full {@code sendCallback} path (signing, retries,
 * RestTemplate). This keeps assertions focused on the new behaviour.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpService.resolveTargetUrl — DeDi → context.bapUri fallback")
class HttpServiceUrlResolutionTest {

    private static final String SUBSCRIBER_ID = "bap.example.com";
    private static final String RECORD_ID     = "key-001";
    private static final String DEDI_URL      = "https://dedi.example.com/beckn";
    private static final String BAP_URL       = "https://bap.example.com/beckn";
    private static final String STATIC_URL    = "http://onix-discover:8080/caller";

    @Mock private RestTemplate restTemplate;
    @Mock private BecknAuth becknAuth;
    @Mock private SigningProperties signingProperties;
    @Mock private DispatcherMetrics dispatcherMetrics;
    @Mock private HttpClientProperties httpClientProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StaticCallbackProperties staticCallback;

    @BeforeEach
    void setUp() {
        staticCallback = new StaticCallbackProperties();
        // ensure RestTemplate stub is harmless even if path executes (lenient = no UnnecessaryStubbingException)
        lenient().when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));
    }

    private HttpService build() {
        return new HttpService(restTemplate, objectMapper, becknAuth, signingProperties,
                dispatcherMetrics, httpClientProperties, staticCallback);
    }

    private static ObjectNode contextWithBapUri(String bapUri) {
        ObjectNode ctx = new ObjectMapper().createObjectNode();
        ctx.put("action", "on_discover");
        if (bapUri != null) ctx.put("bapUri", bapUri);
        return ctx;
    }

    private static String invokeResolve(HttpService svc, String action, String subId,
                                        String recId, ObjectNode context) throws Exception {
        Method m = HttpService.class.getDeclaredMethod(
                "resolveTargetUrl", String.class, String.class, String.class, ObjectNode.class);
        m.setAccessible(true);
        try {
            return (String) m.invoke(svc, action, subId, recId, context);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Unwrap so assertThatThrownBy sees the real cause
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    @Nested
    @DisplayName("static-callback OFF (direct delivery)")
    class StaticOff {

        @Test
        @DisplayName("DeDi resolves → overrides context.bapUri and POSTs to DeDi URL")
        void dediOverridesContext() throws Exception {
            staticCallback.setEnabled(false);
            when(becknAuth.getRegistryEntry(SUBSCRIBER_ID, RECORD_ID))
                    .thenReturn(new RegistryEntry(null, DEDI_URL));
            ObjectNode ctx = contextWithBapUri(BAP_URL);

            String url = invokeResolve(build(), "on_discover", SUBSCRIBER_ID, RECORD_ID, ctx);

            assertThat(url).isEqualTo(DEDI_URL + "/on_discover");
            assertThat(ctx.get("bapUri").asText()).isEqualTo(DEDI_URL);  // overwritten
        }

        @Test
        @DisplayName("DeDi blank → keeps context.bapUri, POSTs to BAP-preserved URL")
        void dediBlankFallsBackToContext() throws Exception {
            staticCallback.setEnabled(false);
            when(becknAuth.getRegistryEntry(SUBSCRIBER_ID, RECORD_ID))
                    .thenReturn(new RegistryEntry(null, ""));
            ObjectNode ctx = contextWithBapUri(BAP_URL);

            String url = invokeResolve(build(), "on_discover", SUBSCRIBER_ID, RECORD_ID, ctx);

            assertThat(url).isEqualTo(BAP_URL + "/on_discover");
            assertThat(ctx.get("bapUri").asText()).isEqualTo(BAP_URL);   // unchanged
        }

        @Test
        @DisplayName("DeDi throws → keeps context.bapUri, POSTs to BAP-preserved URL")
        void dediThrowsFallsBackToContext() throws Exception {
            staticCallback.setEnabled(false);
            when(becknAuth.getRegistryEntry(SUBSCRIBER_ID, RECORD_ID))
                    .thenThrow(new RuntimeException("registry timeout"));
            ObjectNode ctx = contextWithBapUri(BAP_URL);

            String url = invokeResolve(build(), "on_discover", SUBSCRIBER_ID, RECORD_ID, ctx);

            assertThat(url).isEqualTo(BAP_URL + "/on_discover");
            assertThat(ctx.get("bapUri").asText()).isEqualTo(BAP_URL);
        }

        @Test
        @DisplayName("DeDi blank + context.bapUri missing → throws")
        void neitherSourceUsableThrows() {
            staticCallback.setEnabled(false);
            when(becknAuth.getRegistryEntry(SUBSCRIBER_ID, RECORD_ID))
                    .thenReturn(new RegistryEntry(null, ""));
            ObjectNode ctx = contextWithBapUri(null);

            HttpService svc = build();
            assertThatThrownBy(() -> invokeResolve(svc, "on_discover", SUBSCRIBER_ID, RECORD_ID, ctx))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No usable bapUri");
        }
    }

    @Nested
    @DisplayName("static-callback ON (routed via onix)")
    class StaticOn {

        @BeforeEach
        void enableStatic() {
            staticCallback.setEnabled(true);
            staticCallback.setUrl(STATIC_URL);
        }

        @Test
        @DisplayName("DeDi resolves → overrides context.bapUri and POSTs to static (onix) URL")
        void dediStampsContextAndReturnsStaticUrl() throws Exception {
            when(becknAuth.getRegistryEntry(SUBSCRIBER_ID, RECORD_ID))
                    .thenReturn(new RegistryEntry(null, DEDI_URL));
            ObjectNode ctx = contextWithBapUri(BAP_URL);

            String url = invokeResolve(build(), "on_discover", SUBSCRIBER_ID, RECORD_ID, ctx);

            assertThat(url).isEqualTo(STATIC_URL + "/on_discover");
            assertThat(ctx.get("bapUri").asText())
                    .as("DeDi URL stamped into context so onix can route via targetType:bap")
                    .isEqualTo(DEDI_URL);
        }

        @Test
        @DisplayName("DeDi blank → context.bapUri untouched, POSTs to static URL (onix uses BAP-preserved value)")
        void dediBlankKeepsContextAndReturnsStaticUrl() throws Exception {
            when(becknAuth.getRegistryEntry(SUBSCRIBER_ID, RECORD_ID))
                    .thenReturn(new RegistryEntry(null, ""));
            ObjectNode ctx = contextWithBapUri(BAP_URL);

            String url = invokeResolve(build(), "on_discover", SUBSCRIBER_ID, RECORD_ID, ctx);

            assertThat(url).isEqualTo(STATIC_URL + "/on_discover");
            assertThat(ctx.get("bapUri").asText())
                    .as("DeDi failed → context.bapUri left untouched so onix uses BAP's original")
                    .isEqualTo(BAP_URL);
        }

        @Test
        @DisplayName("DeDi throws → context.bapUri untouched, POSTs to static URL")
        void dediThrowsKeepsContextAndReturnsStaticUrl() throws Exception {
            when(becknAuth.getRegistryEntry(SUBSCRIBER_ID, RECORD_ID))
                    .thenThrow(new RuntimeException("registry down"));
            ObjectNode ctx = contextWithBapUri(BAP_URL);

            String url = invokeResolve(build(), "on_discover", SUBSCRIBER_ID, RECORD_ID, ctx);

            assertThat(url).isEqualTo(STATIC_URL + "/on_discover");
            assertThat(ctx.get("bapUri").asText()).isEqualTo(BAP_URL);
        }
    }
}
