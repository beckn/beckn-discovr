package org.beckn.auth.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.PublicKey;

import org.awaitility.Awaitility;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class CaffeineCacheTest {

    private CaffeineCache cache;
    private PublicKey mockKey;

    @BeforeEach
    void setUp() {
        cache = new CaffeineCache(1, 10); // 1 second TTL, max 10 keys
        mockKey = mock(PublicKey.class);
    }

    @AfterEach
    void tearDown() {
        cache.shutdown();
    }

    @Nested
    @DisplayName("Basic Operations")
    class BasicOperationsTests {

        @Test
        @DisplayName("Stores and retrieves a value with correct type")
        void setAndGet_ReturnsStoredValue() {
            cache.set("key1", mockKey);
            PublicKey retrieved = cache.get("key1", PublicKey.class);
            assertThat(retrieved).isSameAs(mockKey);
        }

        @Test
        @DisplayName("Returns null for a key that was never stored")
        void get_MissingKey_ReturnsNull() {
            assertThat(cache.get("missing-key", PublicKey.class)).isNull();
        }

        @Test
        @DisplayName("Returns null for a type mismatch — does not throw")
        void get_TypeMismatch_ReturnsNull() {
            cache.set("key1", "Not a public key");
            PublicKey retrieved = cache.get("key1", PublicKey.class);
            assertThat(retrieved).isNull();
        }

        @Test
        @DisplayName("Correct type still retrieved when other type stored under different key")
        void get_CorrectType_ReturnsValue() {
            cache.set("str-key", "string value");
            String retrieved = cache.get("str-key", String.class);
            assertThat(retrieved).isEqualTo("string value");
        }

        @Test
        @DisplayName("Null key is silently ignored — no exception")
        void set_NullKey_Ignored() {
            assertThatCode(() -> cache.set(null, mockKey)).doesNotThrowAnyException();
            assertThat(cache.get(null, PublicKey.class)).isNull();
        }

        @Test
        @DisplayName("Null value is silently ignored — no exception")
        void set_NullValue_Ignored() {
            assertThatCode(() -> cache.set("key1", null)).doesNotThrowAnyException();
            assertThat(cache.get("key1", PublicKey.class)).isNull();
        }
    }

    @Nested
    @DisplayName("Delete & Clear")
    class DeleteAndClearTests {

        @Test
        @DisplayName("delete() removes the specified key")
        void delete_RemovesKey() {
            cache.set("key1", mockKey);
            assertThat(cache.get("key1", PublicKey.class)).isNotNull();

            cache.delete("key1");
            assertThat(cache.get("key1", PublicKey.class)).isNull();
        }

        @Test
        @DisplayName("delete() on a null key is silently ignored")
        void delete_NullKey_Ignored() {
            assertThatCode(() -> cache.delete(null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("delete() on a non-existent key is silently ignored")
        void delete_NonExistentKey_Ignored() {
            assertThatCode(() -> cache.delete("no-such-key")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("clear() removes all stored entries")
        void clear_RemovesAllEntries() {
            cache.set("a", mockKey);
            cache.set("b", mockKey);
            cache.set("c", "some-string");

            cache.clear();

            assertThat(cache.get("a", PublicKey.class)).isNull();
            assertThat(cache.get("b", PublicKey.class)).isNull();
            assertThat(cache.get("c", String.class)).isNull();
        }
    }

    @Nested
    @DisplayName("Size")
    class SizeTests {

        @Test
        @DisplayName("size() reflects the current number of stored entries")
        void size_ReflectsStoredEntries() {
            assertThat(cache.size()).isEqualTo(0);

            cache.set("key1", mockKey);
            cache.set("key2", "value2");

            assertThat(cache.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("size() decreases after delete()")
        void size_DecreasesAfterDelete() {
            cache.set("key1", mockKey);
            cache.set("key2", mockKey);
            assertThat(cache.size()).isEqualTo(2);

            cache.delete("key1");
            assertThat(cache.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("size() is zero after clear()")
        void size_ZeroAfterClear() {
            cache.set("key1", mockKey);
            cache.set("key2", mockKey);

            cache.clear();

            assertThat(cache.size()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("TTL Expiry")
    class TtlTests {

        @Test
        @DisplayName("Entry expires after TTL elapses")
        void get_Expired_ReturnsNull() {
            // Cache is configured with 1 second TTL
            cache.set("key1", mockKey);
            assertThat(cache.get("key1", PublicKey.class)).isNotNull();

            Awaitility.await()
                    .atMost(3, TimeUnit.SECONDS)
                    .until(() -> cache.get("key1", PublicKey.class) == null);
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("shutdown() is safe to call multiple times")
        void shutdown_SafeToCallMultipleTimes() {
            cache.shutdown();
            assertThatCode(() -> cache.shutdown()).doesNotThrowAnyException();
        }
    }
}
