# Beckn Auth Java SDK

A lightweight Java library for signing and verifying Beckn Protocol HTTP requests using Ed25519 signatures.

---

## What it does

### Signing outgoing requests

Signs outgoing Beckn requests on behalf of a subscriber. Given the raw JSON request body, the SDK hashes it with BLAKE2b-512, signs the digest with an Ed25519 private key, and returns a complete `Authorization` header ready to attach to the HTTP request.

### Verifying incoming requests

Verifies the `Authorization` or `X-Gateway-Authorization` header on incoming Beckn requests. The SDK parses the header, validates the algorithm and timestamps (with configurable clock-skew tolerance), fetches the sender's Ed25519 public key from the Beckn Protocol registry, and cryptographically verifies the signature against the raw request body.

Registry lookups are cached (Caffeine, 30-day TTL by default) to avoid redundant network calls on repeat requests from the same subscriber.

### General

| Feature | Description |
|---------|-------------|
| **Structured errors** | All failures surface as `BecknAuthException` with an HTTP status code and a machine-readable error code (e.g. `SEC_SIGNATURE_INVALID`, `SEC_KEY_NOT_FOUND`) |
| **Pluggable logging** | Auto-detects SLF4J; implement the `Logger` interface to use any other system |
| **Fail-fast init** | Private key and registry client are initialized at construction time — misconfiguration surfaces at startup |
| **Thread-safe** | Immutable after construction; one instance can be shared across all request-handling threads |

**Required runtime dependencies:** BouncyCastle (`bcprov-jdk18on`), Jackson (`jackson-databind`), Caffeine, SLF4J.

---

## Prerequisites

- Java 17 or later
- Gradle (the wrapper `gradlew` is included — no separate installation needed)

---

## Building the SDK

All commands run from the `beckn-auth-java-sdk/` subdirectory:

```bash
cd beckn-auth-java-sdk

# Compile, run all tests, and generate coverage report
./gradlew build

# Compile only (skip tests)
./gradlew compileJava

# Install to local Maven repository (~/.m2) for use in other local projects
./gradlew publishToMavenLocal
```

A successful build produces:
- JAR: `build/libs/beckn-auth-sdk-1.0.0-SNAPSHOT.jar`
- Coverage report: `build/reports/jacoco/test/html/index.html`

---

## Installation

### As a local project dependency

```gradle
dependencies {
    implementation project(':beckn-auth-java-sdk')
}
```

### Published to Maven local

```gradle
dependencies {
    implementation 'org.beckn:beckn-auth-sdk:1.0.0-SNAPSHOT'
}
```

You must also include the required runtime dependencies:

```gradle
implementation 'org.bouncycastle:bcprov-jdk18on:1.77'
implementation 'com.fasterxml.jackson.core:jackson-databind:2.16.1'
implementation 'com.github.ben-manes.caffeine:caffeine:3.1.8'
implementation 'org.slf4j:slf4j-api:2.0.9'
```

---

## Quick Start

### Signing an outgoing request

```java
BecknAuth auth = new BecknAuth(BecknAuthConfig.builder()
    .signingEnabled(true)
    .subscriberId("example-bap.com")
    .keyIdSuffix("ae3ea24b-cfec-495e-81f8-044aaef164ac")
    .privateKey("Base64EncodedEd25519PrivateKey")
    .build());

// Pass the exact raw JSON string that will be sent over the wire
String authorizationHeader = auth.generateAuthHeader(rawRequestBody);
```

### Verifying an incoming request

```java
BecknAuth auth = new BecknAuth(BecknAuthConfig.builder()
    .verificationEnabled(true)
    .registryBaseUrl("https://registry.becknprotocol.io/subscribers")
    .registryName("keys")
    .build());

try {
    ParsedAuthHeader parsed = auth.verifySignature(authorizationHeader, rawRequestBody);
    // parsed.subscriberId(), parsed.uniqueKeyId(), parsed.created(), parsed.expires()
} catch (BecknAuthException e) {
    // e.getHttpStatus() → 400/401/500
    // e.getCode()       → SEC_SIGNATURE_INVALID, SEC_KEY_NOT_FOUND, etc.
    // e.getMessage()    → human-readable description
}
```

> **Important:** Always pass the **exact raw JSON string** received over the wire. Do not compact, pretty-print, or modify it — the signature is tied to the exact bytes.

---

## Configuration Reference

### Signing fields

| Builder method | Description | Default |
|----------------|-------------|---------|
| `signingEnabled(boolean)` | Enable signing | `false` |
| `subscriberId(String)` | Subscriber ID (bap_id / bpp_id) | *(required when signing)* |
| `keyIdSuffix(String)` | Key UUID registered in the Beckn registry | *(required when signing)* |
| `privateKey(String)` | Ed25519 private key — PKCS#8 Base64 or raw 32-byte Base64 | *(required when signing)* |
| `expirySeconds(long)` | Signature validity window | `3600` |

### Verification fields

| Builder method | Description | Default |
|----------------|-------------|---------|
| `verificationEnabled(boolean)` | Enable verification | `false` |
| `registryBaseUrl(String)` | Beckn registry base URL | *(required when verifying)* |
| `registryName(String)` | Registry endpoint segment | *(required when verifying)* |
| `registryToken(String)` | Optional Bearer token for registry API | `null` |
| `cacheTtlSeconds(long)` | Public key cache TTL | `2592000` (30 days) |
| `cacheMaxKeys(int)` | Maximum cached keys | `100` |
| `cacheEnabled(boolean)` | Enable public key caching | `true` |
| `retryAttempts(int)` | Max registry HTTP retry attempts | `3` |
| `timeoutSeconds(int)` | Registry request timeout | `10` |

### Advanced tuning

| Builder method | Description | Default |
|----------------|-------------|---------|
| `allowedClockSkewSeconds(long)` | Timestamp clock skew tolerance | `30` |
| `retryInitialDelayMs(int)` | Initial exponential backoff delay | `500` ms |
| `retryMaxDelayMs(int)` | Maximum backoff delay | `5000` ms |

---

## Error Handling

`verifySignature` and `generateAuthHeader` throw `BecknAuthException` on any failure.

### Error codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `SEC_SIGNATURE_MISSING` | 400 | `Authorization` header absent |
| `SEC_SIGNATURE_INVALID` | 400/401 | Header malformed, algorithm wrong, timestamp expired, or cryptographic mismatch |
| `SEC_SUBSCRIBER_NOT_FOUND` | 400 | `subscriberId` in keyId is empty |
| `SEC_KEY_NOT_FOUND` | 401 | Public key not found in registry |
| `SEC_KEY_EXPIRED_OR_REVOKED` | 401 | Registry key state is not `live` |
| `INTERNAL_ERROR` | 500 | SDK misconfiguration or unexpected internal error |
| `NET_INTERNAL_ERROR` | 500 | Registry unreachable after all retries |

### Returning a Beckn NACK response

```java
@RestControllerAdvice
public class BecknExceptionHandler {

    @ExceptionHandler(BecknAuthException.class)
    public ResponseEntity<AckResponse> handleBecknAuthError(BecknAuthException ex) {
        String transactionId = ""; // extract from request context
        return ResponseEntity
            .status(ex.getHttpStatus())
            .body(AckResponse.fromException(ex, transactionId));
    }
}
```

---

## Integration Examples

### discovery-service-v2

```java
@Bean
public BecknAuth becknAuth(DiscoveryProperties props) {
    DiscoveryProperties.RegistryAuthConfig r = props.getRegistryAuth();
    return new BecknAuth(BecknAuthConfig.builder()
        .verificationEnabled(r.isEnabled())
        .registryBaseUrl(r.getBaseUrl())
        .registryName(r.getRegistryName())
        .registryToken(r.getRegistryToken())
        .cacheTtlSeconds(r.getCacheTtlSeconds())
        .cacheMaxKeys(r.getCacheMaxKeys())
        .cacheEnabled(r.isCacheEnabled())
        .retryAttempts(r.getRetryAttempts())
        .timeoutSeconds(r.getTimeoutSeconds())
        .build());
}
```

Then inject `BecknAuth` and call `verifySignature`:

```java
@Autowired private BecknAuth becknAuth;

// In your request handler:
ParsedAuthHeader parsed = becknAuth.verifySignature(authHeader, rawRequestBody);
```

### response-dispatcher

```java
@Bean
public BecknAuth becknAuth(
        @Value("${signing.enabled:false}") boolean signingEnabled,
        @Value("${signing.subscriber-id:}") String subscriberId,
        @Value("${signing.key-id-suffix:}") String keyIdSuffix,
        @Value("${signing.private-key:}") String privateKey,
        @Value("${signing.expiry-seconds:3600}") long expirySeconds) {

    return new BecknAuth(BecknAuthConfig.builder()
        .signingEnabled(signingEnabled)
        .subscriberId(subscriberId)
        .keyIdSuffix(keyIdSuffix)
        .privateKey(privateKey)
        .expirySeconds(expirySeconds)
        .build());
}
```

Then inject and call `generateAuthHeader`:

```java
@Autowired private BecknAuth becknAuth;

String authHeader = becknAuth.generateAuthHeader(rawRequestBody);
```

---

## Lifecycle Management

The cache uses background threads for cleanup. Always call `shutdown()` during application shutdown to prevent thread leaks:

```java
@PreDestroy
public void onShutdown() {
    becknAuth.shutdown();
}
```

---

## Pluggable Implementations

### Custom Cache (e.g. Redis)

Implement the `Cache` interface to replace Caffeine with any backing store:

```java
public class RedisCacheAdapter implements org.beckn.auth.cache.Cache {
    @Autowired private StringRedisTemplate redis;

    @Override public <T> T get(String key, Class<T> type) { /* redis lookup */ }
    @Override public void set(String key, Object value) { /* redis write */ }
    @Override public void delete(String key) { redis.delete(key); }
    @Override public void clear() { /* flush */ }
    @Override public int size() { return 0; }
    @Override public void shutdown() { /* no-op */ }
}
```

> **Note:** Logger and Cache implementations are currently auto-detected from the classpath (SLF4J and Caffeine respectively). To plug in a custom implementation, implement the `Logger` or `Cache` interface and contribute it via classpath detection or submit a PR to add builder support.

---

## HTTP Signature Format

The `Authorization` header format follows the Beckn HTTP Signature specification:

```
Signature keyId="{subscriberId}|{uniqueKeyId}|ed25519",
          algorithm="ed25519",
          created="{unix_epoch_seconds}",
          expires="{unix_epoch_seconds}",
          headers="(created) (expires) digest",
          signature="{base64_ed25519_signature}"
```

The signing string (input to Ed25519) is:

```
(created): {unix_epoch_seconds}
(expires): {unix_epoch_seconds}
digest: BLAKE-512={base64_blake2b512_of_raw_body}
```
