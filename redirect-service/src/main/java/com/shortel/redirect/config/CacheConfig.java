package com.shortel.redirect.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine JVM-level L1 cache (spec §10.3).
 *
 * Sits in front of the Redis L2 cache:
 *   L1 Caffeine (this)  →  L2 Redis  →  L3 MySQL
 *
 * "resolvedUrls" cache:
 *   - max 200 entries per JVM pod (covers the hot-URL set with ~zero memory overhead)
 *   - expireAfterWrite 5 min — bounds staleness in the absence of an invalidation event
 *   - Invalidated proactively on URL update/deactivate via Kafka broadcast (UrlEventConsumer)
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("resolvedUrls");
        manager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .recordStats());
        return manager;
    }
}
