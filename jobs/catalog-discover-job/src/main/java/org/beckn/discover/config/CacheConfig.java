package org.beckn.discover.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Spring Cache configuration.
 *
 * <p>Enables the Spring Cache abstraction and registers named Caffeine caches
 * with independent TTLs:</p>
 * <ul>
 *   <li><b>schema</b> — API schema loaded by {@link org.beckn.discover.service.validation.SchemaLoaderService}.
 *       TTL: {@code discovery.schema.cache-ttl-hours} (default 1 h).
 *       Used via {@code @Cacheable("schema")} — replaces manual {@code CacheService} schema slots.</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    CacheManager cacheManager(DiscoveryProperties props) {
        long ttlHours = props.getSchema().getCacheTtlHours();

        CaffeineCache schemaCache = new CaffeineCache("schema",
                Caffeine.newBuilder()
                        .expireAfterWrite(ttlHours, TimeUnit.HOURS)
                        .maximumSize(16)
                        .build());

        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(schemaCache));
        return manager;
    }
}
