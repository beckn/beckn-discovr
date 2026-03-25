package org.beckn.seeker.service.signature;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SignatureHeaderBuilderTest {

    private SignatureHeaderBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SignatureHeaderBuilder();
    }

    @Test
    void shouldBuildSigningString() {
        long created = 1234567890L;
        long expires = 1234571490L;
        String digest = "testDigest123";
        
        String signingString = builder.buildSigningString(created, expires, digest);
        
        assertThat(signingString).isEqualTo(
            "(created): 1234567890\n(expires): 1234571490\ndigest: BLAKE-512=testDigest123"
        );
    }

    @Test
    void shouldBuildAuthorizationHeader() {
        String subscriberId = "example.com";
        String uniqueKeyId = "key123";
        long created = 1234567890L;
        long expires = 1234571490L;
        String signature = "testSignature";
        
        String authHeader = builder.buildAuthorizationHeader(subscriberId, uniqueKeyId, created, expires, signature);
        
        assertThat(authHeader).contains("Signature keyId=\"example.com|key123|ed25519\"");
        assertThat(authHeader).contains("algorithm=\"ed25519\"");
        assertThat(authHeader).contains("created=\"1234567890\"");
        assertThat(authHeader).contains("expires=\"1234571490\"");
        assertThat(authHeader).contains("headers=\"(created) (expires) digest\"");
        assertThat(authHeader).contains("signature=\"testSignature\"");
    }
}
