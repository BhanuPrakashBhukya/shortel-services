package com.shortel.url.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortel.url.client.IdGeneratorClient;
import com.shortel.url.client.TenantClient;
import com.shortel.url.dto.CreateUrlRequest;
import com.shortel.url.dto.UpdateUrlRequest;
import com.shortel.url.entity.ShortenedUrl;
import com.shortel.url.repository.UrlRepository;
import com.shortel.url.util.Base62Encoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlService {

    private static final String CACHE_PREFIX = "url:";
    private static final Duration CACHE_TTL  = Duration.ofHours(24);

    /**
     * Aliases that are reserved — either path segments used by other services
     * or words that should not appear in public short links.
     */
    private static final Set<String> RESERVED_ALIASES = Set.of(
        // Gateway / service path prefixes
        "api", "auth", "admin", "www", "app", "static", "assets", "public",
        "resolve", "redirect", "health", "actuator", "metrics",
        // Auth paths
        "login", "logout", "register", "token", "refresh", "validate",
        // Reserved words
        "null", "undefined", "root", "system", "internal", "test",
        // Common profanity (representative — extend as needed)
        "fuck", "shit", "ass", "bitch", "cunt", "dick", "porn", "sex",
        "nude", "nsfw", "hate", "kill", "rape"
    );

    private final UrlRepository     urlRepository;
    private final IdGeneratorClient idGeneratorClient;
    private final TenantClient      tenantClient;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public ShortenedUrl create(CreateUrlRequest req, Long tenantId, Long userId) {
        // Quota check (fail-open by design — TenantClient swallows its own exceptions)
        if (!tenantClient.checkUrlQuota(tenantId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "URL quota exceeded for tenant " + tenantId);
        }

        ShortenedUrl url = new ShortenedUrl();
        url.setTenantId(tenantId);
        url.setLongUrl(req.getLongUrl());
        url.setExpiresAt(req.getExpiresAt());
        url.setCreatedBy(userId);  // from X-User-Id header, not request body

        if (req.getVisibility() != null) {
            url.setVisibility(ShortenedUrl.Visibility.valueOf(req.getVisibility().toUpperCase()));
        }

        // Custom alias or Snowflake-generated code
        if (req.getCustomAlias() != null && !req.getCustomAlias().isBlank()) {
            validateAlias(req.getCustomAlias());
            if (urlRepository.existsByShortCode(req.getCustomAlias())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Alias already taken: " + req.getCustomAlias());
            }
            url.setShortCode(req.getCustomAlias());
            url.setId(idGeneratorClient.nextId());
        } else {
            long snowflakeId = idGeneratorClient.nextId();
            url.setId(snowflakeId);
            url.setShortCode(Base62Encoder.encode(snowflakeId));
        }

        ShortenedUrl saved = urlRepository.save(url);

        // Write-through: prime Redis cache immediately (outside transaction boundary — best-effort)
        cacheUrl(saved);

        // Increment quota usage
        tenantClient.incrementUrlUsage(tenantId);

        // Publish url.created event
        publishUrlEvent("URL_CREATED", saved);

        log.info("Created URL: code={} tenant={} createdBy={}", saved.getShortCode(), tenantId, userId);
        return saved;
    }

    public Optional<ShortenedUrl> findByCode(String code) {
        return urlRepository.findByShortCodeAndActiveTrue(code);
    }

    public List<ShortenedUrl> findByTenant(Long tenantId, Long createdBy) {
        if (createdBy != null) {
            return urlRepository.findByTenantIdAndCreatedByAndActiveTrueOrderByCreatedAtDesc(tenantId, createdBy);
        }
        return urlRepository.findByTenantIdAndActiveTrueOrderByCreatedAtDesc(tenantId);
    }

    @Transactional
    public ShortenedUrl update(String code, UpdateUrlRequest req, Long tenantId, Long userId) {
        ShortenedUrl url = urlRepository.findByShortCodeAndActiveTrue(code)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Short code not found: " + code));

        // Tenant isolation: prevent cross-tenant updates
        if (!url.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "URL does not belong to your tenant");
        }

        // Apply partial updates — only non-null fields are changed
        if (req.getLongUrl() != null) {
            url.setLongUrl(req.getLongUrl());
        }
        if (req.getVisibility() != null) {
            url.setVisibility(ShortenedUrl.Visibility.valueOf(req.getVisibility().toUpperCase()));
        }
        if (req.getExpiresAt() != null) {
            url.setExpiresAt(req.getExpiresAt());
        }

        ShortenedUrl saved = urlRepository.save(url);

        // Invalidate old cache entry and re-prime with updated values
        redis.delete(CACHE_PREFIX + code);
        cacheUrl(saved);

        // Broadcast to invalidate Caffeine L1 caches in redirect-service pods
        publishUrlEvent("URL_UPDATED", saved);

        log.info("Updated URL: code={} tenant={} userId={}", code, tenantId, userId);
        return saved;
    }

    @Transactional
    public void deactivate(String code, Long tenantId, Long userId) {
        ShortenedUrl url = urlRepository.findByShortCodeAndActiveTrue(code)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Short code not found: " + code));

        // Tenant isolation: prevent cross-tenant deactivation
        if (!url.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "URL does not belong to your tenant");
        }

        url.setActive(false);
        urlRepository.save(url);
        redis.delete(CACHE_PREFIX + code);

        // Broadcast to invalidate Caffeine L1 caches in redirect-service pods
        publishUrlEvent("URL_DEACTIVATED", url);

        log.info("Deactivated URL: code={} tenant={} userId={}", code, tenantId, userId);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Rejects aliases that are reserved path segments or contain blocked words.
     * Check is case-insensitive so "API", "Admin", etc. are also blocked.
     */
    private void validateAlias(String alias) {
        if (RESERVED_ALIASES.contains(alias.toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "The alias '" + alias + "' is reserved and cannot be used");
        }
        // Block aliases that contain any reserved/profane word as a substring
        String lower = alias.toLowerCase();
        for (String reserved : RESERVED_ALIASES) {
            if (reserved.length() >= 4 && lower.contains(reserved)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The alias contains a reserved or blocked word");
            }
        }
    }

    private void cacheUrl(ShortenedUrl url) {
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
                        "tenantId",     url.getTenantId(),
                        "active",       url.isActive(),
                        "passwordHash", url.getPasswordHash() != null ? url.getPasswordHash() : ""
                    )), ttl);
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to cache URL {}: {}", url.getShortCode(), e.getMessage());
        }
    }

    private void publishUrlEvent(String eventType, ShortenedUrl url) {
        try {
            String event = objectMapper.writeValueAsString(Map.of(
                "event",     eventType,
                "shortCode", url.getShortCode(),
                "urlId",     url.getId(),
                "tenantId",  url.getTenantId(),
                "timestamp", LocalDateTime.now().toString()
            ));
            kafkaTemplate.send("shortel.url-events", url.getShortCode(), event);
        } catch (Exception e) {
            log.warn("Failed to publish {} event: {}", eventType, e.getMessage());
        }
    }
}
