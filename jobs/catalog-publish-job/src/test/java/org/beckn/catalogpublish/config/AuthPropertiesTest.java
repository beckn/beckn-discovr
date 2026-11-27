package org.beckn.catalogpublish.config;

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
}
