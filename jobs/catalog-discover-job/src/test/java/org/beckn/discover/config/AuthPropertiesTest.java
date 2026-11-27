package org.beckn.discover.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthPropertiesTest {

    @Test
    void nonPositiveCacheMaxKeysDefaultsTo10000() {
        AuthProperties props = new AuthProperties(
                false, "", "keys", "", 30, 2592000, 0, 10, 3, List.of());
        assertThat(props.cacheMaxKeys()).isEqualTo(10000);
    }

    @Test
    void explicitCacheMaxKeysPreserved() {
        AuthProperties props = new AuthProperties(
                true, "https://registry.example/", "keys", "tok",
                30, 2592000, 2500, 10, 3, List.of());
        assertThat(props.cacheMaxKeys()).isEqualTo(2500);
    }
}
