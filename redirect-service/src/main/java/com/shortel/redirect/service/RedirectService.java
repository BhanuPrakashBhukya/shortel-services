package com.shortel.redirect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortel.redirect.entity.ShortenedUrl;
import com.shortel.redirect.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Critical redirect path — cache-first, zero synchronous DB writes.
 * Redis hit → respond. Cache miss → DB read → populate cache → respond.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedirectService {

    private static final String CACHE_PREFIX = "url:";
    private static final Duration CACHE_TTL  = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final UrlRepository       urlRepository;
    private final ObjectMapper        objectMapper;

    /**
     * @param passwordHash BCrypt hash if the URL is password-protected; null otherwise.
     */
    public record ResolvedUrl(String longUrl, ShortenedUrl.Visibility visibility,
                               Long urlId, Long tenantId, boolean expired, String passwordHash) {}

    /**
     * Resolves a short code to its URL metadata.
     *
     * L1: Caffeine in-process cache (this annotation) — sub-millisecond, max 200 entries, 5-min TTL.
     * L2: Redis — checked inside the method body on Caffeine miss.
     * L3: MySQL read replica fallback — populates L2 on DB hit.
     *
     * Empty results (404) are never cached so transient DB misses don't persist.
     */
    @Cacheable(value = "resolvedUrls", key = "#code", unless = "#result == null || #result.isEmpty()")
    public Optional<ResolvedUrl> resolve(String code) {
        // L2: Redis cache lookup
        String cached = redis.opsForValue().get(CACHE_PREFIX + code);
        if (cached != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.readValue(cached, Map.class);
                String expiresAtStr = (String) data.get("expiresAt");
                boolean expired = expiresAtStr != null && !expiresAtStr.isBlank()
                    && LocalDateTime.now().isAfter(LocalDateTime.parse(expiresAtStr));
                String pwHash = (String) data.get("passwordHash");
                return Optional.of(new ResolvedUrl(
                    (String) data.get("longUrl"),
                    ShortenedUrl.Visibility.valueOf((String) data.getOrDefault("visibility", "PUBLIC")),
                    data.get("urlId") != null ? ((Number) data.get("urlId")).longValue() : null,
                    data.get("tenantId") != null ? ((Number) data.get("tenantId")).longValue() : null,
                    expired,
                    (pwHash != null && !pwHash.isBlank()) ? pwHash : null
                ));
            } catch (Exception e) {
                log.warn("Cache parse error for code {}: {}", code, e.getMessage());
            }
        }

        // L3: MySQL read replica fallback
        Optional<ShortenedUrl> dbResult = urlRepository.findByShortCodeAndActiveTrue(code);
        dbResult.ifPresent(this::populateCache);

        return dbResult.map(u -> new ResolvedUrl(
            u.getLongUrl(), u.getVisibility(), u.getId(), u.getTenantId(), u.isExpired(),
            u.getPasswordHash()
        ));
    }

    /**
     * Evicts the given code from the Caffeine L1 cache.
     * Called by {@link com.shortel.redirect.consumer.UrlEventConsumer} on URL_UPDATED
     * and URL_DEACTIVATED events so that stale entries are purged immediately
     * rather than waiting for the 5-minute TTL.
     */
    @CacheEvict(value = "resolvedUrls", key = "#code")
    public void evictFromL1Cache(String code) {
        // Annotation handles eviction — no method body needed
    }

    private void populateCache(ShortenedUrl url) {
        try {
            Duration ttl = url.getExpiresAt() != null
                ? Duration.between(LocalDateTime.now(), url.getExpiresAt()).withNanos(0)
                : CACHE_TTL;
            if (!ttl.isNegative() && !ttl.isZero()) {
                redis.opsForValue().set(CACHE_PREFIX + url.getShortCode(),
                    objectMapper.writeValueAsString(Map.of(
                        "longUrl",      url.getLongUrl(),
                        "visibility",   url.getVisibility().name(),
                        "expiresAt",    url.getExpiresAt() != null ? url.getExpiresAt().toString() : "",
                        "urlId",        url.getId(),
                        "tenantId",     url.getTenantId(),
                        "passwordHash", url.getPasswordHash() != null ? url.getPasswordHash() : ""
                    )), ttl);
            }
        } catch (Exception e) {
            log.warn("Failed to populate cache for {}: {}", url.getShortCode(), e.getMessage());
        }
    }
}
