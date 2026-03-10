package org.beckn.discover.service;

import java.time.Duration;

import org.beckn.discover.config.DiscoveryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import jakarta.annotation.PostConstruct;

/**
 * Registry public-key cache.
 *
 * <p>Provides a single Caffeine cache for registry public keys with TTL from
 * {@code discovery.registry-auth.cache-ttl-seconds} (default 30 days).</p>
 *
 * <p>Schema caching is handled separately by the Spring Cache abstraction
 * ({@code @Cacheable("schema")} in {@link org.beckn.discover.service.validation.SchemaLoaderService})
 * with its own TTL configured in {@link org.beckn.discover.config.CacheConfig}.</p>
 */
@Service
public class CacheService {

    private static final Logger logger = LoggerFactory.getLogger(CacheService.class);

    private final DiscoveryProperties discoveryProperties;

    private Cache<String, Object> keyCache;

    public CacheService(DiscoveryProperties discoveryProperties) {
        this.discoveryProperties = discoveryProperties;
    }

    @PostConstruct
    public void init() {
        int keyTtlSeconds = discoveryProperties.getRegistryAuth().getCacheTtlSeconds();
        long maxKeys      = discoveryProperties.getRegistryAuth().getCacheMaxKeys();

        this.keyCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(keyTtlSeconds))
                .maximumSize(maxKeys)
                .build();

        logger.info("CacheService initialized [keyTtl: {}s, keyMax: {}, cacheEnabled: {}]",
                keyTtlSeconds, maxKeys, discoveryProperties.getRegistryAuth().isCacheEnabled());
    }

    public Object get(String key) {
        Object value = keyCache.getIfPresent(key);
        logger.debug("Cache {} [key: {}]", value != null ? "hit" : "miss", key);
        return value;
    }

    public <T> T get(String key, Class<T> type) {
        Object value = keyCache.getIfPresent(key);
        if (value != null && type.isInstance(value)) {
            logger.debug("Cache hit [key: {}]", key);
            return type.cast(value);
        }
        return null;
    }

    public void put(String key, Object value) {
        if (key != null && value != null) {
            keyCache.put(key, value);
            logger.debug("Cache set [key: {}]", key);
        }
    }

    public void evict(String key) {
        if (key != null) {
            keyCache.invalidate(key);
            logger.debug("Cache evicted [key: {}]", key);
        }
    }

    public void clear() {
        keyCache.invalidateAll();
        logger.info("Key cache cleared");
    }
}
