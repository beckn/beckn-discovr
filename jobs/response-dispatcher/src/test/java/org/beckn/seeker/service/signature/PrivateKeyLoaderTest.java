package org.beckn.seeker.service.signature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.PrivateKey;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class PrivateKeyLoaderTest {

    @InjectMocks
    private PrivateKeyLoader privateKeyLoader;

    private static final String PKCS8_WITH_HEADERS = 
        "-----BEGIN PRIVATE KEY-----\n" +
        "MC4CAQAwBQYDK2VwBCIEIN4CepsxIlxdzPUA9fBxoxaa/iwDUNEi//n0YNQQkxvr\n" +
        "-----END PRIVATE KEY-----";
    
    private static final String PKCS8_WITHOUT_HEADERS = 
        "MC4CAQAwBQYDK2VwBCIEIN4CepsxIlxdzPUA9fBxoxaa/iwDUNEi//n0YNQQkxvr";

    private static final String RAW_32_BYTE_SEED = 
        "3gJ6mzEiXF3M9QD18HGjFpr+LANQ0SL/+fRg1BCTG+s=";

    @Test
    void shouldLoadPKCS8WithHeaders() throws Exception {
        PrivateKey privateKey = privateKeyLoader.loadPrivateKey(PKCS8_WITH_HEADERS);
        
        assertThat(privateKey).isNotNull();
        assertThat(privateKey.getAlgorithm()).isEqualTo("EdDSA");
    }

    @Test
    void shouldLoadPKCS8WithoutHeaders() throws Exception {
        PrivateKey privateKey = privateKeyLoader.loadPrivateKey(PKCS8_WITHOUT_HEADERS);
        
        assertThat(privateKey).isNotNull();
        assertThat(privateKey.getAlgorithm()).isEqualTo("EdDSA");
    }

    @Test
    void shouldLoadRaw32ByteSeed() throws Exception {
        PrivateKey privateKey = privateKeyLoader.loadPrivateKey(RAW_32_BYTE_SEED);
        
        assertThat(privateKey).isNotNull();
        assertThat(privateKey.getAlgorithm()).isEqualTo("EdDSA");
    }

    @Test
    void shouldThrowExceptionForEmptyKey() {
        assertThatThrownBy(() -> privateKeyLoader.loadPrivateKey(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Private key content is empty");
    }

    @Test
    void shouldThrowExceptionForOnlyHeaders() {
        String onlyHeaders = "-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----";
        
        assertThatThrownBy(() -> privateKeyLoader.loadPrivateKey(onlyHeaders))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Private key content is empty");
    }

    @Test
    void shouldThrowExceptionForInvalidLength() {
        String invalidKey = "aGVsbG8="; // 5 bytes
        
        assertThatThrownBy(() -> privateKeyLoader.loadPrivateKey(invalidKey))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid private key length")
            .hasMessageContaining("Expected 32 (raw seed) or 48 (PKCS#8)");
    }
}
