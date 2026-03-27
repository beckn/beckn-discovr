package org.beckn.auth.cache;

/**
 * Pluggable cache interface for the Beckn Auth SDK.
 * <p>
 * Use {@link CacheFactory#createCache} to obtain the best available
 * implementation at runtime.
 * </p>
 */
public interface Cache {

    /**
     * Retrieves a cached value, performing a type-safe cast.
     *
     * @param key  the cache key
     * @param type the expected class of the cached value
     * @param <T>  the value type
     * @return the cached value, or {@code null} if not found, expired, or type mismatch
     */
    <T> T get(String key, Class<T> type);

    /**
     * Stores a value in the cache under the given key.
     * <p>
     * Null keys or null values are silently ignored.
     * </p>
     *
     * @param key   the cache key
     * @param value the value to cache
     */
    void set(String key, Object value);

    /**
     * Removes the entry for the given key if it exists.
     *
     * @param key the cache key to remove
     */
    void delete(String key);

    /** Removes all entries from the cache. */
    void clear();

    /**
     * Returns the current number of entries in the cache.
     * For Caffeine, this is an estimated size after cleanup.
     *
     * @return entry count
     */
    int size();

    /**
     * Shuts down any background threads used by this cache implementation.
     * <p>
     * Must be called when the SDK instance is no longer needed to prevent
     * thread leaks in long-running applications or during context reloads.
     * </p>
     */
    void shutdown();
}
