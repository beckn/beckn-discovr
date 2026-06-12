package org.beckn.discover.service.authorization;

import org.beckn.discover.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuthorizationService#authHttpStatus(String, int)} (F-12):
 * a missing/malformed Authorization header is an authentication failure (401), not 400.
 */
class AuthorizationServiceTest {

    @Test
    void missingHeader_isMappedTo401() {
        assertThat(AuthorizationService.authHttpStatus(ErrorCodes.SEC_SIGNATURE_MISSING, 400)).isEqualTo(401);
    }

    @Test
    void malformedHeader_isMappedTo401() {
        assertThat(AuthorizationService.authHttpStatus(ErrorCodes.SEC_SIGNATURE_INVALID, 400)).isEqualTo(401);
    }

    @Test
    void otherCodes_keepSdkStatus() {
        assertThat(AuthorizationService.authHttpStatus(ErrorCodes.SEC_KEY_NOT_FOUND, 401)).isEqualTo(401);
        assertThat(AuthorizationService.authHttpStatus("NET_INTERNAL_ERROR", 502)).isEqualTo(502);
        assertThat(AuthorizationService.authHttpStatus(null, 500)).isEqualTo(500);
    }
}
