package org.beckn.catalogpublish.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.catalogpublish.config.AppProperties;
import org.beckn.catalogpublish.metrics.CatalogPublishMetrics;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Unit tests for the SSRF-guard toggle on the on_pull download path
 * ({@code app.catalog.pull-ssrf-check-enabled}).
 *
 * <p>{@code validateDownloadUrl(...)} is private, so the toggle is exercised through the public
 * {@link CatalogPullCallbackService#downloadCatalogFromUrl(String)}. The SSRF guard runs BEFORE any
 * network call and rejects loopback/private hosts with an {@link IllegalArgumentException}. So:</p>
 * <ul>
 *   <li><b>enabled (default):</b> a loopback URL is rejected with {@code IllegalArgumentException}
 *       (mentioning the private/loopback address) — the network call is never attempted.</li>
 *   <li><b>disabled:</b> the guard is skipped, so the loopback URL is accepted by the validator and
 *       execution proceeds to the network call. There is therefore NO {@code IllegalArgumentException}
 *       carrying the SSRF rejection message — any failure is a downstream/IO failure, proving the
 *       guard did not reject the URL.</li>
 * </ul>
 */
class CatalogPullCallbackServiceSsrfToggleTest {

    // A loopback URL on a port that is (almost certainly) closed so the network call fails fast
    // rather than hanging — what matters for the toggle is WHICH exception we get.
    private static final String LOOPBACK_URL = "http://127.0.0.1:1/catalog.json.gz";

    private CatalogPullCallbackService newService(boolean ssrfCheckEnabled) {
        CatalogPushService pushService = Mockito.mock(CatalogPushService.class);
        CatalogPublishMetrics metrics = Mockito.mock(CatalogPublishMetrics.class);
        return new CatalogPullCallbackService(pushService, new ObjectMapper(), metrics,
                appProps(ssrfCheckEnabled));
    }

    @Test
    void enabled_rejectsLoopbackUrl_withSsrfError() {
        CatalogPullCallbackService service = newService(true);

        assertThatThrownBy(() -> service.downloadCatalogFromUrl(LOOPBACK_URL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private/loopback");
    }

    @Test
    void disabled_skipsSsrfGuard_loopbackUrlNotRejected() throws Exception {
        CatalogPullCallbackService service = newService(false);

        // Guard skipped => the URL is accepted by validation and the call proceeds to the network.
        // We must NOT get the SSRF IllegalArgumentException; the only failure (if any) is a
        // downstream/IO failure from the unreachable loopback port.
        Throwable thrown = catchThrowable(() -> service.downloadCatalogFromUrl(LOOPBACK_URL));

        if (thrown != null) {
            assertThat(thrown).isNotInstanceOf(IllegalArgumentException.class);
            assertThat(String.valueOf(thrown.getMessage())).doesNotContain("private/loopback");
        }
    }

    @Test
    void absentFlag_defaultsToEnabled_andRejectsLoopback() {
        // Boolean component left null => compact constructor must default it to TRUE (secure-by-default).
        AppProperties.Catalog catalog = catalogWith(null);
        assertThat(catalog.pullSsrfCheckEnabled()).isTrue();

        CatalogPushService pushService = Mockito.mock(CatalogPushService.class);
        CatalogPublishMetrics metrics = Mockito.mock(CatalogPublishMetrics.class);
        CatalogPullCallbackService service = new CatalogPullCallbackService(
                pushService, new ObjectMapper(), metrics,
                new AppProperties(null, null, catalog));

        assertThatThrownBy(() -> service.downloadCatalogFromUrl(LOOPBACK_URL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private/loopback");
    }

    private static AppProperties appProps(boolean ssrfCheckEnabled) {
        return new AppProperties(null, null, catalogWith(ssrfCheckEnabled));
    }

    private static AppProperties.Catalog catalogWith(Boolean ssrfCheckEnabled) {
        return new AppProperties.Catalog(
                10_000_000L, false,
                "https://raw.githubusercontent.com/beckn/protocol-specifications-v2/refs/heads/main/api/v2.0.0/beckn.yaml",
                1, 4, null, null, null, ssrfCheckEnabled, null, null, null, null);
    }
}
