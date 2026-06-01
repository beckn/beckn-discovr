package org.beckn.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.beckn.auth.exception.BecknAuthException;
import org.beckn.auth.model.AckResponse;
import org.beckn.auth.model.ParsedAuthHeader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BecknAuthTest {

    private static WireMockServer wireMockServer;

    private static PrivateKey testPrivateKey;
    private static PublicKey testPublicKey;
    private static String privateKeyPem;
    private static String publicKeyBase64;

    private BecknAuth becknAuthSigner;
    private BecknAuth becknAuthVerifier;

    private final String rawRequestBody = """
            {
              "context": {
                "transaction_id": "txn-123",
                "message_id": "msg-456"
              },
              "message": { "intent": {} }
            }""";

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = kpg.generateKeyPair();
        testPrivateKey = keyPair.getPrivate();
        testPublicKey = keyPair.getPublic();

        privateKeyPem = Base64.getEncoder().encodeToString(testPrivateKey.getEncoded());
        publicKeyBase64 = Base64.getEncoder().encodeToString(testPublicKey.getEncoded());
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

        BecknAuthConfig signerConfig = BecknAuthConfig.builder()
                .signingEnabled(true)
                .subscriberId("example-bap.com")
                .keyIdSuffix("key-1")
                .privateKey(privateKeyPem)
                .build();
        becknAuthSigner = new BecknAuth(signerConfig);

        BecknAuthConfig verifierConfig = BecknAuthConfig.builder()
                .verificationEnabled(true)
                .registryBaseUrl("http://localhost:" + wireMockServer.port() + "/subscribers")
                .registryName("keys")
                .build();
        becknAuthVerifier = new BecknAuth(verifierConfig);
    }

    /** Stubs the registry with a valid live-key response using the test keypair. */
    private void stubRegistryLookup() {
        String jsonResponse = """
                {
                  "data": [
                    { "state": "live", "signing_public_key": "%s" }
                  ]
                }
                """.formatted(publicKeyBase64);

        wireMockServer.stubFor(get(urlEqualTo("/subscribers/example-bap.com/keys/key-1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(jsonResponse)));
    }

    @Nested
    @DisplayName("End-to-End Sign & Verify")
    class EndToEndTests {

        @Test
        @DisplayName("Sign and verify successfully")
        void endToEnd_SignAndVerify_Success() {
            stubRegistryLookup();

            String authHeader = becknAuthSigner.signPayload(rawRequestBody);
            assertThat(authHeader).startsWith("Signature ");

            var result = becknAuthVerifier.verifySignature(authHeader, rawRequestBody);

            assertThat(result.parsedHeader().subscriberId()).isEqualTo("example-bap.com");
            assertThat(result.parsedHeader().uniqueKeyId()).isEqualTo("key-1");
            wireMockServer.verify(1, getRequestedFor(urlEqualTo("/subscribers/example-bap.com/keys/key-1")));
        }

        @Test
        @DisplayName("Second verification uses cache — no extra registry call")
        void endToEnd_SecondVerify_UsesCacheNoExtraRegistryCall() {
            stubRegistryLookup();

            String authHeader = becknAuthSigner.signPayload(rawRequestBody);

            becknAuthVerifier.verifySignature(authHeader, rawRequestBody);
            becknAuthVerifier.verifySignature(authHeader, rawRequestBody); // should hit cache

            // Registry must only be contacted once despite two verifications
            wireMockServer.verify(1, getRequestedFor(urlEqualTo("/subscribers/example-bap.com/keys/key-1")));
        }

        @Test
        @DisplayName("Tampered body fails verification with SEC_SIGNATURE_INVALID")
        void endToEnd_TamperedBody_Fails() {
            stubRegistryLookup();

            String authHeader = becknAuthSigner.signPayload(rawRequestBody);
            String tamperedBody = rawRequestBody.replace("msg-456", "msg-999");

            assertThatThrownBy(() -> becknAuthVerifier.verifySignature(authHeader, tamperedBody))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Authorization verification failed");
        }

        @Test
        @DisplayName("Body with Beckn context has transaction_id and message_id extracted for logging")
        void endToEnd_ContextExtraction() {
            stubRegistryLookup();

            // The body has a well-formed context — main check is that no exception is thrown
            String authHeader = becknAuthSigner.signPayload(rawRequestBody);
            assertThatCode(() -> becknAuthVerifier.verifySignature(authHeader, rawRequestBody))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Body without Beckn context logs 'unknown' but still succeeds")
        void endToEnd_NoContext_StillSucceeds() {
            // Different body — need a signer+verifier using the same keypair for this one
            String noContextBody = "{\"message\":{\"intent\":{}}}";

            // Stub for the signer's key lookup
            String jsonResponse = """
                    {
                      "data": [
                        { "state": "live", "signing_public_key": "%s" }
                      ]
                    }
                    """.formatted(publicKeyBase64);
            wireMockServer.stubFor(get(urlEqualTo("/subscribers/example-bap.com/keys/key-1"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(jsonResponse)));

            String authHeader = becknAuthSigner.signPayload(noContextBody);
            assertThatCode(() -> becknAuthVerifier.verifySignature(authHeader, noContextBody))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Registry Error Scenarios")
    class RegistryErrorTests {

        @Test
        @DisplayName("Registry 404 throws SEC_KEY_NOT_FOUND")
        void verify_Registry404_ThrowsKeyNotFound() {
            wireMockServer.stubFor(get(urlPathMatching("/subscribers/.*"))
                    .willReturn(aResponse().withStatus(404)));

            String authHeader = becknAuthSigner.signPayload(rawRequestBody);

            assertThatThrownBy(() -> becknAuthVerifier.verifySignature(authHeader, rawRequestBody))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Credentials not found");
        }
    }

    @Nested
    @DisplayName("Misconfiguration Guards")
    class MisconfigurationTests {

        @Test
        @DisplayName("Signing on a verification-only instance throws INTERNAL_ERROR")
        void signerFails_VerificationOnlyConfig() {
            BecknAuth verifyOnly = new BecknAuth(BecknAuthConfig.builder()
                    .verificationEnabled(true)
                    .registryBaseUrl("http://localhost:" + wireMockServer.port())
                    .registryName("keys")
                    .build());

            assertThatThrownBy(() -> verifyOnly.signPayload(rawRequestBody))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Service configuration error");
        }

        @Test
        @DisplayName("Verifying on a signing-only instance throws INTERNAL_ERROR")
        void verifierFails_SigningOnlyConfig() {
            assertThatThrownBy(() -> becknAuthSigner.verifySignature("Signature some-val", rawRequestBody))
                    .isInstanceOf(BecknAuthException.class)
                    .hasMessageContaining("Verification not configured");
        }

        @Test
        @DisplayName("Instance with both capabilities enabled can sign and verify")
        void bothCapabilities_WorkTogether() {
            stubRegistryLookup();

            BecknAuthConfig bothConfig = BecknAuthConfig.builder()
                    .signingEnabled(true)
                    .subscriberId("example-bap.com")
                    .keyIdSuffix("key-1")
                    .privateKey(privateKeyPem)
                    .verificationEnabled(true)
                    .registryBaseUrl("http://localhost:" + wireMockServer.port() + "/subscribers")
                    .registryName("keys")
                    .build();
            BecknAuth bothAuth = new BecknAuth(bothConfig);

            String authHeader = bothAuth.signPayload(rawRequestBody);
            var result = bothAuth.verifySignature(authHeader, rawRequestBody);

            assertThat(result.parsedHeader().subscriberId()).isEqualTo("example-bap.com");
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("shutdown() completes without error")
        void shutdown_DoesNotThrow() {
            assertThatCode(() -> becknAuthSigner.shutdown()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("AckResponse Integration")
    class AckResponseTests {

        @Test
        @DisplayName("fromException builds correct NACK response")
        void ackResponse_FromException() {
            BecknAuthException ex = BecknAuthException.invalidHeader("Bad header", "SEC_SIGNATURE_INVALID");

            AckResponse nack = AckResponse.fromException(ex);

            assertThat(nack.status()).isEqualTo("NACK");
            assertThat(nack.error()).isNotNull();
            assertThat(nack.error().errorCode()).isEqualTo("SEC_SIGNATURE_INVALID");
            assertThat(nack.error().errorMessage()).isEqualTo("Bad header");
        }

        @Test
        @DisplayName("ack() builds correct ACK response")
        void ackResponse_Ack() {
            AckResponse ack = AckResponse.ack();

            assertThat(ack.status()).isEqualTo("ACK");
            assertThat(ack.error()).isNull();
        }
    }
}
