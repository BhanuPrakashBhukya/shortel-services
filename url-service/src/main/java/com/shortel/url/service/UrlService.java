package com.shortel.url.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortel.url.client.IdGeneratorClient;
import com.shortel.url.client.TenantClient;
import com.shortel.url.dto.CreateUrlRequest;
import com.shortel.url.entity.ShortenedUrl;
import com.shortel.url.repository.UrlRepository;
import com.shortel.url.util.Base62Encoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlService {

    private static final String CACHE_PREFIX = "url:";
    private static final Duration CACHE_TTL  = Duration.ofHours(24);

    private final UrlRepository     urlRepository;
    private final IdGeneratorClient idGeneratorClient;
    private final TenantClient      tenantClient;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public ShortenedUrl create(CreateUrlRequest req) {
        Long tenantId = req.getTenantId() != null ? req.getTenantId() : 1L;

        // Quota check
        if (!tenantClient.checkUrlQuota(tenantId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "URL quota exceeded for tenant " + tenantId);
        }

        ShortenedUrl url = new ShortenedUrl();
        url.setTenantId(tenantId);
        url.setLongUrl(req.getLongUrl());
        url.setExpiresAt(req.getExpiresAt());
        url.setCreatedBy(req.getCreatedBy());

        if (req.getVisibility() != null) {
            url.setVisibility(ShortenedUrl.Visibility.valueOf(req.getVisibility().toUpperCase()));
        }

        // Custom alias or Snowflake-generated code
        if (req.getCustomAlias() != null && !req.getCustomAlias().isBlank()) {
            if (urlRepository.existsByShortCode(req.getCustomAlias())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Alias already taken: " + req.getCustomAlias());
            }
            url.setShortCode(req.getCustomAlias());
            url.setId(idGeneratorClient.nextId()); // still need a unique ID
        } else {
            long snowflakeId = idGeneratorClient.nextId();
            url.setId(snowflakeId);
            url.setShortCode(Base62Encoder.encode(snowflakeId));
        }

        ShortenedUrl saved = urlRepository.save(url);

        // Write-through: prime Redis cache immediately
        cacheUrl(saved);

        // Increment quota usage (async-ish via non-blocking call)
        tenantClient.incrementUrlUsage(tenantId);

        // Publish url.created event
        publishUrlCreatedEvent(saved);

        log.info("Created URL: code={} tenant={}", saved.getShortCode(), tenantId);
        return saved;
    }

    public Optional<ShortenedUrl> findByCode(String code) {
        return urlRepository.findByShortCodeAndActiveTrue(code);
    }

    public List<ShortenedUrl> findByTenant(Long tenantId) {
        return urlRepository.findByTenantIdAndActiveTrueOrderByCreatedAtDesc(tenantId);
    }

    public ShortenedUrl deactivate(String code) {
        ShortenedUrl url = urlRepository.findByShortCodeAndActiveTrue(code)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Short code not found: " + code));
        url.setActive(false);
        ShortenedUrl saved = urlRepository.save(url);
        redis.delete(CACHE_PREFIX + code);
        return saved;
    }

    private void cacheUrl(ShortenedUrl url) {
        try {
            Duration ttl = url.getExpiresAt() != null
                ? Duration.between(LocalDateTime.now(), url.getExpiresAt()).withNanos(0)
                : CACHE_TTL;
            if (!ttl.isNegative() && !ttl.isZero()) {
                redis.opsForValue().set(CACHE_PREFIX + url.getShortCode(),
                    objectMapper.writeValueAsString(Map.of(
                        "longUrl",    url.getLongUrl(),
                        "visibility", url.getVisibility().name(),
                        "expiresAt",  url.getExpiresAt() != null ? url.getExpiresAt().toString() : "",
                        "tenantId",   url.getTenantId(),
                        "active",     url.isActive()
                    )), ttl);
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to cache URL {}: {}", url.getShortCode(), e.getMessage());
        }
    }

    private void publishUrlCreatedEvent(ShortenedUrl url) {
        try {
            String event = objectMapper.writeValueAsString(Map.of(
                "event",     "URL_CREATED",
                "shortCode", url.getShortCode(),
                "tenantId",  url.getTenantId(),
                "timestamp", LocalDateTime.now().toString()
            ));
            kafkaTemplate.send("shortel.url-events", url.getShortCode(), event);
        } catch (Exception e) {
            log.warn("Failed to publish URL created event: {}", e.getMessage());
        }
    }
}
