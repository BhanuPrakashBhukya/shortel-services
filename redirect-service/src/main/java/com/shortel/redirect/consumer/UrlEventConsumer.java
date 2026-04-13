package com.shortel.redirect.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortel.redirect.service.AclCacheService;
import com.shortel.redirect.service.RedirectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Listens to url-service events and invalidates the L1 Caffeine cache
 * and L2 Redis cache when a URL is updated or deactivated (spec §6.2).
 *
 * Group: redirect-cache-invalidation — auto-offset-reset=latest so that
 * on pod restart only new events are processed (past events are irrelevant
 * since the pod's Caffeine cache is cold anyway).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UrlEventConsumer {

    private static final String CACHE_PREFIX = "url:";

    private final RedirectService     redirectService;
    private final AclCacheService     aclCacheService;
    private final StringRedisTemplate redis;
    private final ObjectMapper        objectMapper;

    @KafkaListener(topics = "shortel.url-events", groupId = "redirect-cache-invalidation")
    public void onUrlEvent(@Payload String message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = objectMapper.readValue(message, Map.class);

            String type = (String) event.getOrDefault("event", "");
            String code = (String) event.getOrDefault("shortCode", "");
            Long   urlId = event.get("urlId") != null
                ? ((Number) event.get("urlId")).longValue() : null;

            if (code.isBlank()) return;

            switch (type) {
                case "URL_UPDATED", "URL_DEACTIVATED" -> {
                    // Evict from L1 Caffeine cache (this pod)
                    redirectService.evictFromL1Cache(code);
                    // Evict from L2 Redis cache (shared across all pods)
                    redis.delete(CACHE_PREFIX + code);
                    // Evict all ACL cache entries for this URL
                    if (urlId != null) {
                        aclCacheService.evictAll(urlId);
                    }
                    log.debug("Cache invalidated: event={} code={}", type, code);
                }
                // URL_CREATED: no eviction needed — new URL, nothing cached yet
                default -> { /* no-op */ }
            }
        } catch (Exception e) {
            // Never throw — offset will be committed so the event doesn't block
            log.warn("Failed to process URL event for cache invalidation: {}", e.getMessage());
        }
    }
}
