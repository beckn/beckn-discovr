package org.beckn.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BecknAuthConfigTest {

    @Nested
    @DisplayName("Default Configuration")
    class DefaultsTests {

        @Test
        @DisplayName("Should build with no capabilities enabled by default")
        void build_Defaults_NeitherCapabilityEnabled() {
            BecknAuthConfig config = BecknAuthConfig.builder().build();
            assertThat(config.isSigningEnabled()).isFalse();
            assertThat(config.isVerificationEnabled()).isFalse();
        }

        @Test
        @DisplayName("Default expiry is 3600 seconds")
        void build_DefaultExpirySeconds() {
            BecknAuthConfig config = BecknAuthConfig.builder().build();
            assertThat(config.getExpirySeconds()).isEqualTo(3600L);
        }

        @Test
        @DisplayName("Default clock skew is 30 seconds")
        void build_DefaultClockSkew() {
            BecknAuthConfig config = BecknAuthConfig.builder().build();
            assertThat(config.getAllowedClockSkewSeconds()).isEqualTo(30L);
        }

        @Test
        @DisplayName("Default cache TTL is 30 days (2592000 seconds)")
        void build_DefaultCacheTtl() {
            BecknAuthConfig config = BecknAuthConfig.builder().build();
            assertThat(config.getCacheTtlSeconds()).isEqualTo(2592000L);
        }

        @Test
        @DisplayName("Default retry attempts is 3")
        void build_DefaultRetryAttempts() {
            BecknAuthConfig config = BecknAuthConfig.builder().build();
            assertThat(config.getRetryAttempts()).isEqualTo(3);
        }

        @Test
        @DisplayName("Logger and cache are auto-detected and non-null")
        void build_LoggerAndCacheAutoDetected() {
            BecknAuthConfig config = BecknAuthConfig.builder().build();
            assertThat(config.getLogger()).isNotNull();
            assertThat(config.getCache()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Signing Validation")
    class SigningValidationTests {

        @Test
        @DisplayName("Fails if signing enabled but subscriberId is missing")
        void build_SigningMissingSubscriberId() {
            assertThatThrownBy(() -> BecknAuthConfig.builder()
                    .signingEnabled(true)
                    .keyIdSuffix("key-1")
                    .privateKey("priv")
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("subscriberId is required");
        }

        @Test
        @DisplayName("Fails if signing enabled but keyIdSuffix is missing")
        void build_SigningMissingKeyIdSuffix() {
            assertThatThrownBy(() -> BecknAuthConfig.builder()
                    .signingEnabled(true)
                    .subscriberId("sub")
                    .privateKey("priv")
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("keyIdSuffix is required");
        }

        @Test
        @DisplayName("Fails if signing enabled but privateKey is missing")
        void build_SigningMissingPrivateKey() {
            assertThatThrownBy(() -> BecknAuthConfig.builder()
                    .signingEnabled(true)
                    .subscriberId("sub")
                    .keyIdSuffix("key-1")
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("privateKey is required");
        }

        @Test
        @DisplayName("Fails if subscriberId is blank (not just null)")
        void build_SigningBlankSubscriberId() {
            assertThatThrownBy(() -> BecknAuthConfig.builder()
                    .signingEnabled(true)
                    .subscriberId("   ")
                    .keyIdSuffix("key-1")
                    .privateKey("priv")
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("subscriberId is required");
        }
    }

    @Nested
    @DisplayName("Verification Validation")
    class VerificationValidationTests {

        @Test
        @DisplayName("Fails if verification enabled but registryBaseUrl is missing")
        void build_VerificationMissingBaseUrl() {
            assertThatThrownBy(() -> BecknAuthConfig.builder()
                    .verificationEnabled(true)
                    .registryName("keys")
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("registryBaseUrl is required");
        }

        @Test
        @DisplayName("Fails if verification enabled but registryName is missing")
        void build_VerificationMissingRegistryName() {
            assertThatThrownBy(() -> BecknAuthConfig.builder()
                    .verificationEnabled(true)
                    .registryBaseUrl("http://reg")
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("registryName is required");
        }
    }

    @Nested
    @DisplayName("Full Configuration")
    class FullConfigTests {

        @Test
        @DisplayName("Should build with all required fields for both capabilities")
        void build_AllCapabilitiesEnabled() {
            BecknAuthConfig config = BecknAuthConfig.builder()
                    .signingEnabled(true)
                    .subscriberId("sub")
                    .keyIdSuffix("key")
                    .privateKey("priv")
                    .verificationEnabled(true)
                    .registryBaseUrl("http://reg")
                    .registryName("keys")
                    .build();

            assertThat(config.isSigningEnabled()).isTrue();
            assertThat(config.isVerificationEnabled()).isTrue();
            assertThat(config.getSubscriberId()).isEqualTo("sub");
            assertThat(config.getKeyIdSuffix()).isEqualTo("key");
            assertThat(config.getRegistryBaseUrl()).isEqualTo("http://reg");
            assertThat(config.getRegistryName()).isEqualTo("keys");
        }

        @Test
        @DisplayName("Custom values are stored correctly")
        void build_CustomValues() {
            BecknAuthConfig config = BecknAuthConfig.builder()
                    .verificationEnabled(true)
                    .registryBaseUrl("http://reg")
                    .registryName("mykeys")
                    .registryToken("tok-abc")
                    .expirySeconds(7200)
                    .allowedClockSkewSeconds(60)
                    .retryAttempts(5)
                    .timeoutSeconds(30)
                    .cacheTtlSeconds(86400)
                    .cacheMaxKeys(500)
                    .build();

            assertThat(config.getRegistryToken()).isEqualTo("tok-abc");
            assertThat(config.getExpirySeconds()).isEqualTo(7200L);
            assertThat(config.getAllowedClockSkewSeconds()).isEqualTo(60L);
            assertThat(config.getRetryAttempts()).isEqualTo(5);
            assertThat(config.getTimeoutSeconds()).isEqualTo(30);
            assertThat(config.getCacheTtlSeconds()).isEqualTo(86400L);
            assertThat(config.getCacheMaxKeys()).isEqualTo(500);
        }
    }
}
