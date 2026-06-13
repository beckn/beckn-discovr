package org.beckn.discover.service.authorization;

import org.beckn.auth.BecknAuth;
import org.beckn.auth.exception.BecknAuthException;
import org.beckn.auth.model.ParsedAuthHeader;
import org.beckn.auth.model.VerificationResult;
import org.beckn.discover.common.ErrorCodes;
import org.beckn.discover.config.AuthProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.web.ErrorResponseException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthorizationService} HTTP status mapping (F-12).
 *
 * <p>Focus: the SDK historically tags a <em>missing</em> or <em>malformed/unparseable</em>
 * Authorization header as HTTP 400. Per RFC 7235 these must surface as 401 Unauthorized
 * carrying a {@code WWW-Authenticate} challenge. The cryptographic-verification path
 * (well-formed header, bad signature → SDK 401) must pass through unchanged.</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthorizationServiceTest {

    private static final String EXPECTED_CHALLENGE = "Signature realm=\"beckn\"";
    private static final String RAW_BODY = "{\"context\":{}}";

    @Mock
    private BecknAuth becknAuth;

    private AuthorizationService newService() {
        // Auth enabled, no whitelisted endpoints — forces the verification path.
        AuthProperties props = new AuthProperties(
                true, "https://registry.example/", "keys", "tok",
                30, 2592000, 100, 10, 3, List.of());
        return new AuthorizationService(becknAuth, props);
    }

    @Test
    void missingHeader_remappedTo401_withWwwAuthenticate() {
        when(becknAuth.verifySignature(any(), any())).thenThrow(
                BecknAuthException.authenticationRequired(
                        "Authorization header is missing",
                        org.beckn.auth.util.ErrorCodes.SEC_SIGNATURE_MISSING, "authorization"));

        ErrorResponseException ex = catchThrowableOfType(
                () -> newService().authorizeRequest(RAW_BODY, new HttpHeaders()),
                ErrorResponseException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(401);
        assertThat(ex.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(EXPECTED_CHALLENGE);
        assertThat(ex.getBody().getProperties().get("code")).isEqualTo(ErrorCodes.AUT_SIGNATURE_MISSING);
    }

    @Test
    void malformedHeader_remappedTo401_withWwwAuthenticate() {
        // invalidHeader(...) is the SDK's 400 factory used for unparseable headers.
        when(becknAuth.verifySignature(any(), any())).thenThrow(
                BecknAuthException.invalidHeader(
                        "Authorization header format is invalid",
                        org.beckn.auth.util.ErrorCodes.SEC_SIGNATURE_INVALID));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "InvalidFormat");

        ErrorResponseException ex = catchThrowableOfType(
                () -> newService().authorizeRequest(RAW_BODY, headers),
                ErrorResponseException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(401);
        assertThat(ex.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(EXPECTED_CHALLENGE);
        assertThat(ex.getBody().getProperties().get("code")).isEqualTo(ErrorCodes.AUT_SIGNATURE_INVALID);
    }

    @Test
    void cryptographicMismatch_staysAsSdk401_unchanged() {
        // signatureVerificationFailed(...) is the SDK's 401 crypto path — must NOT be altered.
        // It reuses SEC_SIGNATURE_INVALID but is already 401, so the remap must leave it alone.
        when(becknAuth.verifySignature(any(), any())).thenThrow(
                BecknAuthException.signatureVerificationFailed(
                        "signature mismatch", org.beckn.auth.util.ErrorCodes.SEC_SIGNATURE_INVALID));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Signature keyId=\"a|b|ed25519\",...");

        ErrorResponseException ex = catchThrowableOfType(
                () -> newService().authorizeRequest(RAW_BODY, headers),
                ErrorResponseException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(401);
        assertThat(ex.getBody().getProperties().get("code")).isEqualTo(ErrorCodes.AUT_SIGNATURE_INVALID);
        // 401s also carry the challenge per RFC 7235 — behaviour preserved.
        assertThat(ex.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(EXPECTED_CHALLENGE);
    }

    @Test
    void keyNotFound_staysAsSdk401_unchanged() {
        when(becknAuth.verifySignature(any(), any())).thenThrow(
                BecknAuthException.keyNotFound("key not in registry"));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Signature keyId=\"a|b|ed25519\",...");

        ErrorResponseException ex = catchThrowableOfType(
                () -> newService().authorizeRequest(RAW_BODY, headers),
                ErrorResponseException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(401);
        assertThat(ex.getBody().getProperties().get("code")).isEqualTo(ErrorCodes.AUT_KEY_NOT_FOUND);
    }

    @Test
    void subscriberNotFound_remappedTo401_withWwwAuthenticate() {
        // SDK tags a blank subscriber in the keyId as 400 (authenticationRequired), but it
        // is a credential-level auth failure → must be 401 + challenge (F-12 / RFC 7235).
        when(becknAuth.verifySignature(any(), any())).thenThrow(
                BecknAuthException.authenticationRequired(
                        "Could not identify the requester",
                        org.beckn.auth.util.ErrorCodes.SEC_SUBSCRIBER_NOT_FOUND, "authorization/keyId"));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Signature keyId=\"|key|ed25519\",...");

        ErrorResponseException ex = catchThrowableOfType(
                () -> newService().authorizeRequest(RAW_BODY, headers),
                ErrorResponseException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(401);
        assertThat(ex.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(EXPECTED_CHALLENGE);
        assertThat(ex.getBody().getProperties().get("code")).isEqualTo(ErrorCodes.AUT_SUBSCRIBER_NOT_FOUND);
    }

    @Test
    void keyExpired_staysAsSdk401_translatedToAutCode() {
        when(becknAuth.verifySignature(any(), any())).thenThrow(
                BecknAuthException.keyExpired("registry key is not live"));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Signature keyId=\"a|b|ed25519\",...");

        ErrorResponseException ex = catchThrowableOfType(
                () -> newService().authorizeRequest(RAW_BODY, headers),
                ErrorResponseException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(401);
        assertThat(ex.getBody().getProperties().get("code")).isEqualTo(ErrorCodes.AUT_KEY_EXPIRED_OR_REVOKED);
        assertThat(ex.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(EXPECTED_CHALLENGE);
    }

    @Test
    void timestampExpired_staysAsSdk401_notRemappedFrom400() {
        // timestampExpired reuses SEC_SIGNATURE_INVALID but is already 401 — the gate
        // (httpStatus==400) must leave it 401, distinct from the missing/malformed remap.
        when(becknAuth.verifySignature(any(), any())).thenThrow(
                BecknAuthException.timestampExpired("signature expired", "authorization"));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Signature keyId=\"a|b|ed25519\",...");

        ErrorResponseException ex = catchThrowableOfType(
                () -> newService().authorizeRequest(RAW_BODY, headers),
                ErrorResponseException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(401);
        assertThat(ex.getBody().getProperties().get("code")).isEqualTo(ErrorCodes.AUT_SIGNATURE_INVALID);
    }

    @Test
    void registryError_staysAsSdk502_noChallenge() {
        // A 502 must not be flipped to 401 and must not gain a WWW-Authenticate header.
        when(becknAuth.verifySignature(any(), any())).thenThrow(
                BecknAuthException.registryError("registry unreachable", new RuntimeException("boom")));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Signature keyId=\"a|b|ed25519\",...");

        ErrorResponseException ex = catchThrowableOfType(
                () -> newService().authorizeRequest(RAW_BODY, headers),
                ErrorResponseException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(502);
        assertThat(ex.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    }

    @Test
    void happyPath_returnsIdentity() {
        ParsedAuthHeader parsed = new ParsedAuthHeader(
                "sub|rec|ed25519", "sub", "rec", "ed25519",
                100L, 200L, "(created) (expires) digest", "sig");
        when(becknAuth.verifySignature(any(), any()))
                .thenReturn(new VerificationResult(parsed, "https://sub.example"));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Signature keyId=\"sub|rec|ed25519\",...");

        AuthorizationService.AuthIdentity identity = newService().authorizeRequest(RAW_BODY, headers);

        assertThat(identity.subscriberId()).isEqualTo("sub");
        assertThat(identity.recordId()).isEqualTo("rec");
    }
}
