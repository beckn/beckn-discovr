package org.beckn.seeker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Static callback routing configuration.
 *
 * <p>When {@code enabled=true}, the response-dispatcher posts every outbound
 * callback to the configured static URL instead of resolving the recipient's
 * URL via the DeDi registry. Intended for routing callbacks through an Onix
 * adapter (or any HTTP proxy that handles signing/routing on behalf of the
 * dispatcher).</p>
 *
 * <p>When {@code enabled=false} (default), the dispatcher uses its existing
 * DeDi-based URL resolution — fully backward-compatible.</p>
 *
 * <p>Fail-fast validation: if {@code enabled=true} the {@code url} must be set,
 * otherwise {@link #afterPropertiesSet()} (invoked by Spring after binding)
 * throws an {@link IllegalStateException} and the application refuses to start.</p>
 */
@Configuration
@ConfigurationProperties(prefix = "static-callback")
public class StaticCallbackProperties {

    private boolean enabled = false;
    private String url = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url;
    }

    /**
     * Validate the post-bind state. Called by Spring after property binding.
     * Fails fast if {@code enabled=true} but {@code url} is missing/blank.
     */
    @jakarta.annotation.PostConstruct
    public void validate() {
        if (enabled && (url == null || url.isBlank())) {
            throw new IllegalStateException(
                "static-callback.url must be set when static-callback.enabled=true");
        }
    }
}
