package org.beckn.auth.verification;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.beckn.auth.BecknAuthConfig;
import org.beckn.auth.crypto.CryptoService;
import org.beckn.auth.exception.BecknAuthException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistryServiceTest {

    private static WireMockServer wireMockServer;
    private static PublicKey testPublicKey;
    private static String testPublicKeyBase64;

    private RegistryService registryService;
    private CryptoService cryptoService;

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = kpg.generateKeyPair();
        testPublicKey = keyPair.getPublic();
        testPublicKeyBase64 = Base64.getEncoder().encodeToString(testPublicKey.getEncoded());
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();

        // Very short retry delays so tests run fast
        BecknAuthConfig config = BecknAuthConfig.builder()
                .verificationEnabled(true)
                .registryBaseUrl("http://localhost:" + wireMockServer.port() + "/subscribers")
                .registryName("keys")
                .retryInitialDelayMs(10)
                .retryMaxDelayMs(50)
                .retryAttempts(3)
                .build();

        cryptoService = new CryptoService(config.getLogger());
        registryService = new RegistryService(config, cryptoService);
    }

    // ─── JSON response helpers ───────────────────────────────────────────────────

    /** Registry response with a top-level array under "data" (common format). */
    private String arrayResponseJson() {
        return """
                {
                  "data": [
                    { "state": "live", "signing_public_key": "%s" }
                  ]
                }
                """.formatted(testPublicKeyBase64);
    }

    /** Registry response with a nested object under "data.details" (alternative format). */
    private String objectDetailsResponseJson() {
        return """
                {
                  "data": {
                    "details": { "state": "live", "signing_public_key": "%s" }
                  }
                }
                """.formatted(testPublicKeyBase64);
    }

    /** Registry response using the "publicKey" field name instead of "signing_public_key". */
    private String publicKeyFieldJson() {
        return """
                {
                  "data": [
                    { "state": "live", "publicKey": "%s" }
                  ]
                }
                """.formatted(testPublicKeyBase64);
    }

    @Nested
    @DisplayName("Successful Lookups")
    class SuccessTests {

        @Test
        @DisplayName("Returns parsed PublicKey on HTTP 200 with array-format response")
        void getPublicKey_ArrayFormat_Success() {
            wireMockServer.stubFor(get(urlEqualTo("/subscribers/bap1/keys/key1"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(arrayResponseJson())));

            PublicKey key = registryService.getPublicKey("bap1", "key1");

            assertThat(key).isNotNull();
            assertThat(key.getAlgorithm()).isEqualTo("EdDSA");
            wireMockServer.verify(1, getRequestedFor(urlEqualTo("/subscribers/bap1/keys/key1")));
        }

        @Test
        @DisplayName("Returns parsed PublicKey with nested object details format")
        void getPublicKey_ObjectDetailsFormat_Success() {
            wireMockServer.stubFor(get(urlEqualTo("/subscribers/bap1/keys/key-obj"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(objectDetailsResponseJson())));

            PublicKey key = registryService.getPublicKey("bap1", "key-obj");

            assertThat(key).isNotNull();
        }

        @Test
        @DisplayName("Returns parsed PublicKey using 'publicKey' field name")
        void getPublicKey_PublicKeyFieldName_Success() {
            wireMockServer.stubFor(get(urlEqualTo("/subscribers/bap1/keys/key-pk"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(publicKeyFieldJson())));

            PublicKey key = registryService.getPublicKey("bap1", "key-pk");

            assertThat(key).isNotNull();
        }

        @Test
        @DisplayName("Second lookup returns cached key — no extra HTTP request")
        void getPublicKey_CacheHit_NoExtraRequest() {
            wireMockServer.stubFor(get(urlEqualTo("/subscribers/bap1/keys/key2"))
                    .willReturn(aResponse().withStatus(200).withBody(arrayResponseJson())));

            PublicKey key1 = registryService.getPublicKey("bap1", "key2");
            PublicKey key2 = registryService.getPublicKey("bap1", "key2");

            assertThat(key1).isSameAs(key2); // exact same object from cache
            wireMockServer.verify(1, getRequestedFor(urlEqualTo("/subscribers/bap1/keys/key2")));
        }
    }

    @Nested
    @DisplayName("Retry Behaviour")
    class RetryTests {

        @Test
        @DisplayName("Retries on HTTP 500 and succeeds on next attempt")
        void getPublicKey_RetriesOn500_ThenSucceeds() {
            wireMockServer.stubFor(get(urlEqualTo("/subscribers/bap1/keys/key3"))
                    .inScenario("Retry500")
                    .whenScenarioStateIs("Started")
                    .willReturn(aResponse().withStatus(500))
                    .willSetStateTo("Attempt2"));

            wireMockServer.stubFor(get(urlEqualTo("/subscribers/bap1/keys/key3"))
                    .inScenario("Retry500")
                    .whenScenarioStateIs("Attempt2")
                    .willReturn(aResponse().withStatus(200).withBody(arrayResponseJson())));

            PublicKey key = registryService.getPublicKey("bap1", "key3");

            assertThat(key).isNotNull();
            wireMockServer.verify(2, getRequestedFor(urlEqualTo("/subscribers/bap1/keys/key3")));
        }

        @Test
        @DisplayName("Retries on HTTP 429 (rate limited) and succeeds on next attempt")
        void getPublicKey_RetriesOn429_ThenSucceeds() {
            wireMockServer.stubFor(get(urlEqualTo("/subscribers/bap1/keys/key-429"))
                    .inScenario("Retry429")
                    .whenScenarioStateIs("Started")
                    .willReturn(aResponse().withStatus(429))
                    .willSetStateTo("Attempt2"));

            wireMockServer.stubFor(get(urlEqualTo("/subscribers/bap1/keys/key-429"))
                    .inScenario("Retry429")
                    .whenScenarioStateIs("Attempt2")
                    .willReturn(aResponse().withStatus(200).withBody(arrayResponseJson())));

            PublicKey key = registryService.getPublicKey("bap1", "key-429");

            assertThat(key).isNotNull();
            wireMockServer.verify(2, getRequestedFor(urlEqualTo("/subscribers/bap1/keys/key-429")));
        }

        @Test
        @DisplayName("All retries exhausted (3× 500) throws NET_INTERNAL_ERROR")
        void getPublicKey_AllRetriesExhausted_ThrowsNetError() {
            wireMockServer.stubFor(get(urlEqualTo("/subscribers/bap1/keys/key-fail"))
                    .willReturn(aResponse().withStatus(500)));

            assertThatThrownBy(() -> registryService.getPublicKey("bap1", "key-fail"))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Registry connection failed");

            wireMockServer.verify(3, getRequestedFor(urlEqualTo("/subscribers/bap1/keys/key-fail")));
        }
    }

    @Nested
    @DisplayName("Error Responses")
    class ErrorResponseTests {

        @Test
        @DisplayName("HTTP 404 fails immediately (no retries) with SEC_KEY_NOT_FOUND")
        void getPublicKey_Http404_FailsImmediately() {
            wireMockServer.stubFor(get(urlEqualTo("/subscribers/bap1/keys/key4"))
                    .willReturn(aResponse().withStatus(404)));

            assertThatThrownBy(() -> registryService.getPublicKey("bap1", "key4"))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Public key not found in registry");

            // Only 1 request — no retries for client errors
            wireMockServer.verify(1, getRequestedFor(urlEqualTo("/subscribers/bap1/keys/key4")));
        }

        @Test
        @DisplayName("HTTP 401 fails immediately (no retries) with SEC_KEY_NOT_FOUND")
        void getPublicKey_Http401_FailsImmediately() {
            wireMockServer.stubFor(get(urlEqualTo("/subscribers/bap1/keys/key-401"))
                    .willReturn(aResponse().withStatus(401)));

            assertThatThrownBy(() -> registryService.getPublicKey("bap1", "key-401"))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Public key not found in registry");

            wireMockServer.verify(1, getRequestedFor(urlEqualTo("/subscribers/bap1/keys/key-401")));
        }

        @Test
        @DisplayName("Key state 'revoked' throws SEC_KEY_EXPIRED_OR_REVOKED")
        void getPublicKey_RevokedState_Throws() {
            String revokedJson = """
                    {
                      "data": [
                        { "state": "revoked", "signing_public_key": "somekey" }
                      ]
                    }
                    """;
            wireMockServer.stubFor(get(urlEqualTo("/subscribers/bap1/keys/key5"))
                    .willReturn(aResponse().withStatus(200).withBody(revokedJson)));

            assertThatThrownBy(() -> registryService.getPublicKey("bap1", "key5"))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("state=revoked");
        }

        @Test
        @DisplayName("Key state 'expired' throws SEC_KEY_EXPIRED_OR_REVOKED")
        void getPublicKey_ExpiredState_Throws() {
            String expiredJson = """
                    {
                      "data": [
                        { "state": "expired", "signing_public_key": "somekey" }
                      ]
                    }
                    """;
            wireMockServer.stubFor(get(urlEqualTo("/subscribers/bap1/keys/key-exp"))
                    .willReturn(aResponse().withStatus(200).withBody(expiredJson)));

            assertThatThrownBy(() -> registryService.getPublicKey("bap1", "key-exp"))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("state=expired");
        }

        @Test
        @DisplayName("Response with no public key field throws SEC_KEY_NOT_FOUND")
        void getPublicKey_NoKeyField_Throws() {
            String noKeyJson = """
                    {
                      "data": [
                        { "state": "live", "some_other_field": "value" }
                      ]
                    }
                    """;
            wireMockServer.stubFor(get(urlEqualTo("/subscribers/bap1/keys/key-nofield"))
                    .willReturn(aResponse().withStatus(200).withBody(noKeyJson)));

            assertThatThrownBy(() -> registryService.getPublicKey("bap1", "key-nofield"))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Public key not found in registry");
        }
    }
}
