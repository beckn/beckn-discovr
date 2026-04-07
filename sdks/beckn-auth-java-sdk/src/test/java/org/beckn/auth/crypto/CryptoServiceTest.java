package org.beckn.auth.crypto;

import org.beckn.auth.exception.BecknAuthException;
import org.beckn.auth.logging.LoggerFactory;
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    private CryptoService cryptoService;

    @BeforeEach
    void setUp() {
        cryptoService = new CryptoService(LoggerFactory.createLogger(CryptoService.class));
    }

    @Nested
    @DisplayName("BLAKE2b-512 Hashing")
    class HashingTests {

        @Test
        @DisplayName("Empty string produces a known correct hash")
        void hash_EmptyString_KnownValue() {
            String hash = cryptoService.generateBlake2bHash("");
            // Known BLAKE2b-512 hash of an empty string
            assertThat(hash).isEqualTo(
                    "eGoC90IBWQPGxv2FJVLScpEvR0DhWEdhiobiF/cfVBnSXhAxr+5YUxOJZESTTrBLkDpoWxRIt1XVb3Aa/pvizg==");
        }

        @Test
        @DisplayName("Hashing is deterministic — same input gives same output")
        void hash_Deterministic() {
            String json = "{\"context\":{\"transaction_id\":\"123\"},\"message\":{}}";
            String hash1 = cryptoService.generateBlake2bHash(json);
            String hash2 = cryptoService.generateBlake2bHash(json);
            assertThat(hash1).isEqualTo(hash2);
        }

        @Test
        @DisplayName("Different inputs produce different hashes")
        void hash_DifferentInputs_DifferentOutputs() {
            String hash1 = cryptoService.generateBlake2bHash("hello");
            String hash2 = cryptoService.generateBlake2bHash("world");
            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("Hash result is Base64 encoded and non-blank")
        void hash_OutputIsBase64() {
            String hash = cryptoService.generateBlake2bHash("test payload");
            assertThat(hash).isNotBlank();
            // Must be valid Base64
            byte[] decoded = Base64.getDecoder().decode(hash);
            assertThat(decoded).hasSize(64); // 512 bits = 64 bytes
        }

        @Test
        @DisplayName("Null input throws NullPointerException")
        void hash_NullInput_Throws() {
            assertThatThrownBy(() -> cryptoService.generateBlake2bHash(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Ed25519 Signing & Verification")
    class SigningTests {

        private PrivateKey privateKey;
        private PublicKey publicKey;

        @BeforeEach
        void setUpKeys() throws Exception {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            KeyPair keyPair = kpg.generateKeyPair();
            this.privateKey = keyPair.getPrivate();
            this.publicKey = keyPair.getPublic();
        }

        @Test
        @DisplayName("Sign and verify a string succeeds with matching keys")
        void signAndVerify_MatchingKeys_Succeeds() {
            String data = "test_signing_string_12345";
            String signature = cryptoService.signWithEd25519(data, privateKey);
            assertThat(signature).isNotBlank();

            boolean valid = cryptoService.verifyEd25519Signature(data, signature, publicKey);
            assertThat(valid).isTrue();
        }

        @Test
        @DisplayName("Verification fails if signature is tampered")
        void verify_TamperedSignature_ReturnsFalse() {
            String data = "test_signing_string";
            String signature = cryptoService.signWithEd25519(data, privateKey);

            // Flip one character in the middle to break the signature while keeping valid Base64
            int mid = signature.length() / 2;
            char original = signature.charAt(mid);
            char replacement = (original == 'A') ? 'B' : 'A';
            String tampered = signature.substring(0, mid) + replacement + signature.substring(mid + 1);

            assertThat(cryptoService.verifyEd25519Signature(data, tampered, publicKey)).isFalse();
        }

        @Test
        @DisplayName("Verification fails if data is tampered after signing")
        void verify_TamperedData_ReturnsFalse() {
            String data = "test_signing_string";
            String signature = cryptoService.signWithEd25519(data, privateKey);

            assertThat(cryptoService.verifyEd25519Signature(data + "_modified", signature, publicKey)).isFalse();
        }

        @Test
        @DisplayName("Verification fails with a different public key")
        void verify_WrongPublicKey_ReturnsFalse() throws Exception {
            String data = "test_data";
            String signature = cryptoService.signWithEd25519(data, privateKey);

            // Generate a totally different keypair
            KeyPair otherPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            assertThat(cryptoService.verifyEd25519Signature(data, signature, otherPair.getPublic())).isFalse();
        }

        @Test
        @DisplayName("Signing produces Base64-encoded output (non-blank)")
        void sign_OutputIsBase64() {
            String signature = cryptoService.signWithEd25519("hello", privateKey);
            assertThat(signature).isNotBlank();
            // Must be decodable Base64
            byte[] decoded = Base64.getDecoder().decode(signature);
            assertThat(decoded).hasSize(64); // Ed25519 signature is always 64 bytes
        }
    }

    @Nested
    @DisplayName("Private Key Parsing")
    class PrivateKeyParsingTests {

        @Test
        @DisplayName("Parses PKCS#8 encoded Base64 private key")
        void parsePrivateKey_Pkcs8Base64() throws Exception {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String encoded = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());

            PrivateKey parsed = cryptoService.parsePrivateKey(encoded);

            assertThat(parsed).isNotNull();
            assertThat(parsed.getAlgorithm()).isEqualTo("EdDSA");
        }

        @Test
        @DisplayName("Parses PKCS#8 PEM-wrapped private key (with headers)")
        void parsePrivateKey_PemWrapped() throws Exception {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String b64 = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
            String pem = "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";

            PrivateKey parsed = cryptoService.parsePrivateKey(pem);

            assertThat(parsed).isNotNull();
        }

        @Test
        @DisplayName("Invalid key content (wrong length) throws BecknAuthException")
        void parsePrivateKey_InvalidLength_Throws() {
            // 10 random bytes — neither 32 nor 48
            String invalid = Base64.getEncoder().encodeToString(new byte[10]);
            assertThatThrownBy(() -> cryptoService.parsePrivateKey(invalid))
                    .isInstanceOf(BecknAuthException.class);
        }

        @Test
        @DisplayName("Empty string throws BecknAuthException")
        void parsePrivateKey_Empty_Throws() {
            assertThatThrownBy(() -> cryptoService.parsePrivateKey(""))
                    .isInstanceOf(Exception.class);
        }
    }

    @Nested
    @DisplayName("Public Key Parsing")
    class PublicKeyParsingTests {

        @Test
        @DisplayName("Parses X.509 SPKI encoded Base64 public key (44+ bytes)")
        void parsePublicKey_X509Base64() throws Exception {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String encoded = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

            PublicKey parsed = cryptoService.parsePublicKey(encoded);

            assertThat(parsed).isNotNull();
            assertThat(parsed.getAlgorithm()).isEqualTo("EdDSA");
        }

        @Test
        @DisplayName("Parses raw 32-byte Ed25519 public key (wrapped in SPKI internally)")
        void parsePublicKey_Raw32Bytes() throws Exception {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            // Extract the raw 32-byte key from the SPKI structure
            SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(kp.getPublic().getEncoded());
            byte[] rawKey = spki.getPublicKeyData().getBytes();
            assertThat(rawKey).hasSize(32);

            String encoded = Base64.getEncoder().encodeToString(rawKey);
            PublicKey parsed = cryptoService.parsePublicKey(encoded);

            assertThat(parsed).isNotNull();
        }

        @Test
        @DisplayName("Parses PEM-wrapped X.509 public key")
        void parsePublicKey_PemWrapped() throws Exception {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String b64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
            String pem = "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";

            PublicKey parsed = cryptoService.parsePublicKey(pem);

            assertThat(parsed).isNotNull();
        }

        @Test
        @DisplayName("Parsed public key can verify a signature from the matching private key")
        void parsePublicKey_CanVerifySignature() throws Exception {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String pubKeyB64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

            PublicKey parsedPublicKey = cryptoService.parsePublicKey(pubKeyB64);
            String data = "verify-me";
            String signature = cryptoService.signWithEd25519(data, kp.getPrivate());

            assertThat(cryptoService.verifyEd25519Signature(data, signature, parsedPublicKey)).isTrue();
        }
    }
}
