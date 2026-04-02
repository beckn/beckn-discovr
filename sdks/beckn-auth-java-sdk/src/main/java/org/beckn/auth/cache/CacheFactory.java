package org.beckn.auth.cache;

/**
 * Factory that auto-detects and creates the best available {@link Cache}
 * implementation on the classpath.
 * <p>
 * Detection order:
 * <ol>
 * <li>If {@code com.github.benmanes.caffeine.cache.Caffeine} is present,
 * returns a {@link CaffeineCache} (near-optimal eviction, high throughput)</li>
 * </ol>
 * </p>
 *
 * @throws IllegalStateException if Caffeine is not found on the classpath
 */
public final class CacheFactory {

    private static final String CAFFEINE_CLASS_NAME = "com.github.benmanes.caffeine.cache.Caffeine";

    private CacheFactory() {
        // Utility class — no instantiation
    }

    /**
     * Creates the best available {@link Cache} implementation.
     *
     * @param ttlSeconds             time-to-live for each cache entry, in seconds
     * @param maxKeys                maximum number of concurrent cache entries
     * @param cleanupIntervalSeconds periodic cleanup interval (ignored for Caffeine)
     * @return a ready-to-use {@link Cache} instance
     * @throws IllegalStateException if Caffeine is not found on the classpath
     */
    public static Cache createCache(long ttlSeconds, int maxKeys, long cleanupIntervalSeconds) {
        if (isCaffeineAvailable()) {
            return new CaffeineCache(ttlSeconds, maxKeys);
        }
        throw new IllegalStateException(
                "No suitable Cache implementation found on classpath. " +
                "Please add 'com.github.ben-manes.caffeine:caffeine' to your dependencies.");
    }

    /**
     * Checks if Caffeine is available on the classpath via reflection.
     *
     * @return {@code true} if Caffeine is present on the classpath
     */
    private static boolean isCaffeineAvailable() {
        try {
            Class.forName(CAFFEINE_CLASS_NAME);
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}
