package org.beckn.auth.cache;

import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;

/**
 * {@link Cache} implementation backed by the Caffeine library.
 * <p>
 * Caffeine provides near-optimal cache eviction (W-TinyLFU policy) and
 * is the preferred implementation when available on the classpath.
 * This class is only instantiated if Caffeine is detected by
 * {@link CacheFactory} via classpath reflection.
 * </p>
 * <p>
 * Entries expire after a fixed write-time TTL and are bounded by a
 * configurable maximum size.
 * </p>
 */
public final class CaffeineCache implements Cache {

    private final com.github.benmanes.caffeine.cache.Cache<String, Object> caffeineCache;

    /**
     * Constructs a CaffeineCache with the given TTL and size limit.
     *
     * @param ttlSeconds time-to-live for each cache entry, in seconds
     * @param maxKeys    maximum number of entries before Caffeine evicts least-used
     */
    public CaffeineCache(long ttlSeconds, int maxKeys) {
        this.caffeineCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(maxKeys)
                .build();
    }

    /**
     * Returns the cached value if present and type-compatible, otherwise {@code null}.
     *
     * @param key  the cache key
     * @param type the expected class of the cached value
     * @param <T>  the value type
     * @return the cached value, or {@code null} if absent or type mismatch
     */
    @Override
    public <T> T get(String key, Class<T> type) {
        if (key == null) {
            return null;
        }
        Object value = caffeineCache.getIfPresent(key);
        if (value != null && type.isInstance(value)) {
            return type.cast(value);
        }
        return null;
    }

    /**
     * Stores a value under the given key. Null keys or values are silently ignored.
     *
     * @param key   the cache key
     * @param value the value to cache
     */
    @Override
    public void set(String key, Object value) {
        if (key != null && value != null) {
            caffeineCache.put(key, value);
        }
    }

    /**
     * Removes the entry for the given key if it exists.
     *
     * @param key the cache key to remove
     */
    @Override
    public void delete(String key) {
        if (key != null) {
            caffeineCache.invalidate(key);
        }
    }

    /** Removes all entries from the Caffeine cache. */
    @Override
    public void clear() {
        caffeineCache.invalidateAll();
    }

    /**
     * Returns the estimated number of entries after triggering a Caffeine cleanup pass.
     *
     * @return estimated entry count
     */
    @Override
    public int size() {
        caffeineCache.cleanUp();
        return (int) caffeineCache.estimatedSize();
    }

    /**
     * Invalidates all cache entries and triggers a final cleanup pass.
     * Safe to call multiple times.
     */
    @Override
    public void shutdown() {
        caffeineCache.invalidateAll();
        caffeineCache.cleanUp();
    }
}
