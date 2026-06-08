package org.beckn.discover.service.authorization;

import org.beckn.auth.BecknAuth;
import org.beckn.discover.config.AuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link AuthorizationService} focusing on the
 * "auth disabled but Authorization header present" path — onix-discover in
 * front cryptographically verifies signatures, and the Java service only
 * needs to extract the caller's identity from the keyId.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthorizationService.authorizeRequest — auth disabled, parse-only mode")
class AuthorizationServiceTest {

    @Mock
    private BecknAuth becknAuth;

    private AuthorizationService service;

    @BeforeEach
    void setUp() {
        // SIGNATURE_AUTH_ENABLED=false — onix in front does the crypto verification.
        AuthProperties props = new AuthProperties(
                false, "", "keys", "", 30L, 2592000L, 100, 10, 3, List.of());
        service = new AuthorizationService(becknAuth, props);
    }

    private static HttpHeaders authHeaders(String authValue) {
        HttpHeaders h = new HttpHeaders();
        if (authValue != null) h.set(HttpHeaders.AUTHORIZATION, authValue);
        return h;
    }

    @Nested
    @DisplayName("with valid Authorization header")
    class ValidHeader {

        @Test
        @DisplayName("extracts (subscriberId, recordId) from keyId — no crypto called")
        void parsesKeyId() {
            String header = "Signature keyId=\"beckn.bap.dev|76EU8gk4hL62rkUovUkbpLSzY8CsG2iQijnE49AEuCeE6hc9ZkL7Ue|ed25519\"," +
                    "algorithm=\"ed25519\",created=\"1\",expires=\"2\",headers=\"(created) (expires) digest\"," +
                    "signature=\"abc==\"";

            var identity = service.authorizeRequest("{}", authHeaders(header));

            assertThat(identity.subscriberId()).isEqualTo("beckn.bap.dev");
            assertThat(identity.recordId()).isEqualTo("76EU8gk4hL62rkUovUkbpLSzY8CsG2iQijnE49AEuCeE6hc9ZkL7Ue");
            verifyNoInteractions(becknAuth);
        }

        @Test
        @DisplayName("keyId with trimmed whitespace is tolerated")
        void parsesWithWhitespaceAroundSegments() {
            String header = "Signature keyId=\" sub.example.com | rec-001 |ed25519\"";

            var identity = service.authorizeRequest("{}", authHeaders(header));

            assertThat(identity.subscriberId()).isEqualTo("sub.example.com");
            assertThat(identity.recordId()).isEqualTo("rec-001");
        }
    }

    @Nested
    @DisplayName("fallback to anonymous")
    class Anonymous {

        @Test
        @DisplayName("no Authorization header → anonymous")
        void missingHeader() {
            var identity = service.authorizeRequest("{}", authHeaders(null));

            assertThat(identity).isEqualTo(AuthorizationService.AuthIdentity.anonymous());
            verifyNoInteractions(becknAuth);
        }

        @Test
        @DisplayName("Authorization header without keyId param → anonymous")
        void headerWithoutKeyId() {
            var identity = service.authorizeRequest("{}", authHeaders("Signature algorithm=\"ed25519\""));

            assertThat(identity).isEqualTo(AuthorizationService.AuthIdentity.anonymous());
        }

        @Test
        @DisplayName("keyId with only one pipe-segment → anonymous (no recordId)")
        void keyIdWithOnlyOneSegment() {
            var identity = service.authorizeRequest("{}", authHeaders("Signature keyId=\"only-subscriber\""));

            assertThat(identity).isEqualTo(AuthorizationService.AuthIdentity.anonymous());
        }

        @Test
        @DisplayName("keyId with blank subscriberId → anonymous")
        void keyIdWithBlankSubscriberId() {
            var identity = service.authorizeRequest("{}", authHeaders("Signature keyId=\"|some-record|ed25519\""));

            assertThat(identity).isEqualTo(AuthorizationService.AuthIdentity.anonymous());
        }

        @Test
        @DisplayName("blank Authorization header → anonymous")
        void blankHeader() {
            var identity = service.authorizeRequest("{}", authHeaders("   "));

            assertThat(identity).isEqualTo(AuthorizationService.AuthIdentity.anonymous());
        }
    }
}
