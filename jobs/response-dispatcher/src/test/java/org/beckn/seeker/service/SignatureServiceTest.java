package org.beckn.seeker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.beckn.seeker.service.signature.CryptoService;
import org.beckn.seeker.service.signature.PrivateKeyLoader;
import org.beckn.seeker.service.signature.SignatureHeaderBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignatureServiceTest {

    private SignatureService signatureService;
    private ObjectMapper objectMapper;
    
    @Mock
    private PrivateKeyLoader privateKeyLoader;
    
    @Mock
    private CryptoService cryptoService;
    
    @Mock
    private SignatureHeaderBuilder signatureHeaderBuilder;
    
    private PrivateKey testPrivateKey;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        signatureService = new SignatureService(objectMapper, privateKeyLoader, cryptoService, signatureHeaderBuilder);
        
        // Load test private key
        String pkcs8Key = "MC4CAQAwBQYDK2VwBCIEIN4CepsxIlxdzPUA9fBxoxaa/iwDUNEi//n0YNQQkxvr";
        byte[] decoded = Base64.getDecoder().decode(pkcs8Key);
        testPrivateKey = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    @Test
    void shouldGenerateAuthHeader() throws Exception {
        // Setup
        ReflectionTestUtils.setField(signatureService, "enabled", true);
        ReflectionTestUtils.setField(signatureService, "subscriberId", "example.com");
        ReflectionTestUtils.setField(signatureService, "uniqueKeyId", "key123");
        ReflectionTestUtils.setField(signatureService, "privateKeyPem", "test-key");
        ReflectionTestUtils.setField(signatureService, "expirySeconds", 3600L);
        ReflectionTestUtils.setField(signatureService, "parsedPrivateKey", testPrivateKey);
        
        // Beckn v2.0: bap_id → bapId
        String requestBody = "{\"context\":{\"bapId\":\"example.com\",\"action\":\"on_discover\"}}";
        
        when(cryptoService.hashMessage(requestBody)).thenReturn("testDigest");
        when(cryptoService.sign(anyString(), any(PrivateKey.class))).thenReturn("testSignature");
        when(signatureHeaderBuilder.buildSigningString(any(Long.class), any(Long.class), anyString()))
            .thenReturn("signingString");
        when(signatureHeaderBuilder.buildAuthorizationHeader(anyString(), anyString(), any(Long.class), any(Long.class), anyString()))
            .thenReturn("Signature keyId=\"example.com|key123|ed25519\"");
        
        // Execute
        String authHeader = signatureService.generateAuthHeader(requestBody);
        
        // Verify
        assertThat(authHeader).isNotNull();
        assertThat(authHeader).contains("example.com|key123|ed25519");
    }

    @Test
    void shouldThrowExceptionWhenSubscriberIdMissing() {
        ReflectionTestUtils.setField(signatureService, "enabled", true);
        ReflectionTestUtils.setField(signatureService, "subscriberId", "");
        ReflectionTestUtils.setField(signatureService, "uniqueKeyId", "key123");
        ReflectionTestUtils.setField(signatureService, "privateKeyPem", "test-key");
        
        assertThatThrownBy(() -> signatureService.init())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("signing.subscriber-id is required");
    }

    @Test
    void shouldThrowExceptionWhenKeyIdMissing() {
        ReflectionTestUtils.setField(signatureService, "enabled", true);
        ReflectionTestUtils.setField(signatureService, "subscriberId", "example.com");
        ReflectionTestUtils.setField(signatureService, "uniqueKeyId", "");
        ReflectionTestUtils.setField(signatureService, "privateKeyPem", "test-key");
        
        assertThatThrownBy(() -> signatureService.init())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("signing.key-id-suffix is required");
    }

    @Test
    void shouldReturnTrueWhenEnabled() {
        ReflectionTestUtils.setField(signatureService, "enabled", true);
        
        assertThat(signatureService.isEnabled()).isTrue();
    }

    @Test
    void shouldReturnFalseWhenDisabled() {
        ReflectionTestUtils.setField(signatureService, "enabled", false);
        
        assertThat(signatureService.isEnabled()).isFalse();
    }
}
