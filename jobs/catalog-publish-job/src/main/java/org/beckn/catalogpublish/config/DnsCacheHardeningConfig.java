package org.beckn.catalogpublish.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

import java.security.Security;

/**
 * Hardens the JVM DNS cache at startup to close the DNS-rebinding TOCTOU on the on_pull secure
 * download path.
 *
 * <p><b>Why this closes the TOCTOU:</b> {@link org.beckn.catalogpublish.controller.SecureCatalogDownloader}
 * resolves the (untrusted) manifest host and rejects any private/loopback address BEFORE fetching. But
 * {@code java.net.http.HttpClient} re-resolves the host at fetch time, so a hostile DNS server returning
 * {@code TTL=0} could flip a validated public IP to a private/loopback IP in the gap between validation
 * and fetch. Setting a POSITIVE {@code networkaddress.cache.ttl} makes the JVM cache the validated
 * positive resolution for that window, so HttpClient's fetch-time lookup reuses the SAME validated IPs
 * and the flip cannot take effect. {@code networkaddress.cache.negative.ttl=0} keeps failed lookups
 * from being cached (fail-closed remains responsive).</p>
 *
 * <p>Full per-connection IP pinning (resolve once, connect to exactly that IP) requires Java 18's
 * {@code InetAddressResolverProvider}; on Java 17 the positive-TTL cache plus the all-address SSRF
 * check ({@code InetAddress.getAllByName}) is the strongest available closure. The SSRF-via-redirect
 * bypass is independently closed by building the HttpClient to never follow redirects.</p>
 */
@Configuration
public class DnsCacheHardeningConfig {

    private static final Logger log = LoggerFactory.getLogger(DnsCacheHardeningConfig.class);

    private final AppProperties props;

    public DnsCacheHardeningConfig(AppProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void hardenDnsCache() {
        int ttlSeconds = props.catalog().pullDnsCacheTtlSeconds();
        // Positive TTL: cache the SSRF-validated resolution so fetch-time re-resolution reuses it.
        Security.setProperty("networkaddress.cache.ttl", Integer.toString(ttlSeconds));
        // Negative TTL 0: do not cache failed lookups (a transient DNS blip shouldn't stick).
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
        log.info("event=dns.cache.hardened positiveTtlSeconds={} negativeTtlSeconds=0", ttlSeconds);
    }
}
