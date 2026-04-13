package com.shortel.redirect.service;

import com.shortel.redirect.repository.UrlAccessListRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed ACL cache for private URL access checks (spec §6.1, §8.3).
 *
 * Key pattern: acl:{urlId}:{userId}
 * Value:       "1" (allowed) or "0" (denied)
 * TTL:         1 hour
 *
 * Cache-aside pattern: check Redis first, fall back to DB on miss, populate cache.
 * This avoids a DB round-trip on every private URL redirect.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AclCacheService {

    private static final String ACL_PREFIX = "acl:";
    private static final Duration ACL_TTL  = Duration.ofHours(1);

    private final StringRedisTemplate         redis;
    private final UrlAccessListRepository     urlAccessListRepository;

    /**
     * Returns {@code true} if {@code userId} is in the ACL for {@code urlId}.
     * Result is cached in Redis for 1 hour (both hits and misses are cached
     * to prevent repeated DB reads for users not in the list).
     */
    public boolean hasAccess(Long urlId, Long userId) {
        String key    = ACL_PREFIX + urlId + ":" + userId;
        String cached = redis.opsForValue().get(key);

        if (cached != null) {
            return "1".equals(cached);
        }

        // Cache miss — query the DB and store result
        boolean allowed = urlAccessListRepository.existsByUrlIdAndUserId(urlId, userId);
        try {
            redis.opsForValue().set(key, allowed ? "1" : "0", ACL_TTL);
        } catch (Exception e) {
            // Redis write failure is non-fatal; fall through with DB result
            log.warn("Failed to cache ACL result for urlId={} userId={}: {}", urlId, userId, e.getMessage());
        }
        return allowed;
    }

    /** Called when a URL's access list changes — invalidates cached decision. */
    public void evict(Long urlId, Long userId) {
        redis.delete(ACL_PREFIX + urlId + ":" + userId);
    }

    /** Evicts all ACL entries for a URL (e.g. when the URL is deactivated). */
    public void evictAll(Long urlId) {
        // SCAN for acl:{urlId}:* to avoid blocking KEYS
        try (var cursor = redis.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                    .match(ACL_PREFIX + urlId + ":*").count(100).build())) {
            cursor.forEachRemaining(redis::delete);
        } catch (Exception e) {
            log.warn("Failed to evict ACL entries for urlId={}: {}", urlId, e.getMessage());
        }
    }
}
