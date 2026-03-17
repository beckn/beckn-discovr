package org.beckn.seeker.service.signature;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class CryptoServiceTest {

    private CryptoService cryptoService;
    private PrivateKey testPrivateKey;

    @BeforeEach
    void setUp() throws Exception {
        cryptoService = new CryptoService();
        cryptoService.init();
        
        // Load test private key (PKCS#8 format)
        String pkcs8Key = "MC4CAQAwBQYDK2VwBCIEIN4CepsxIlxdzPUA9fBxoxaa/iwDUNEi//n0YNQQkxvr";
        byte[] decoded = Base64.getDecoder().decode(pkcs8Key);
        testPrivateKey = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    @Test
    void shouldHashMessageWithBLAKE2b() throws Exception {
        String message = "test message";
        
        String hash = cryptoService.hashMessage(message);
        
        assertThat(hash).isNotNull();
        assertThat(hash).isNotEmpty();
        assertThat(Base64.getDecoder().decode(hash)).hasSize(64); // BLAKE2b-512 = 64 bytes
    }

    @Test
    void shouldProduceDeterministicHash() throws Exception {
        String message = "test message";
        
        String hash1 = cryptoService.hashMessage(message);
        String hash2 = cryptoService.hashMessage(message);
        
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void shouldProduceDifferentHashForDifferentMessages() throws Exception {
        String message1 = "test message 1";
        String message2 = "test message 2";
        
        String hash1 = cryptoService.hashMessage(message1);
        String hash2 = cryptoService.hashMessage(message2);
        
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void shouldSignMessageWithEd25519() throws Exception {
        String message = "test message";
        
        String signature = cryptoService.sign(message, testPrivateKey);
        
        assertThat(signature).isNotNull();
        assertThat(signature).isNotEmpty();
        assertThat(Base64.getDecoder().decode(signature)).hasSize(64); // Ed25519 signature = 64 bytes
    }

    @Test
    void shouldProduceDeterministicSignature() throws Exception {
        String message = "test message";
        
        String sig1 = cryptoService.sign(message, testPrivateKey);
        String sig2 = cryptoService.sign(message, testPrivateKey);
        
        assertThat(sig1).isEqualTo(sig2);
    }

    @Test
    void shouldProduceDifferentSignatureForDifferentMessages() throws Exception {
        String message1 = "test message 1";
        String message2 = "test message 2";
        
        String sig1 = cryptoService.sign(message1, testPrivateKey);
        String sig2 = cryptoService.sign(message2, testPrivateKey);
        
        assertThat(sig1).isNotEqualTo(sig2);
    }
}
