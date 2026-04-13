package com.shortel.tenant.service;

import com.shortel.tenant.entity.Tenant;
import com.shortel.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Real-time quota tracking via Redis counters.
 * Keys: quota:{tenantId}:urls  |  quota:{tenantId}:clicks
 * Synced to MySQL every 60s for billing reports.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final StringRedisTemplate redis;
    private final TenantRepository tenantRepository;

    public boolean checkUrlQuota(Long tenantId) {
        return tenantRepository.findById(tenantId)
            .map(t -> {
                long used = getUsed(urlKey(tenantId));
                return used < t.getUrlQuota();
            })
            .orElse(false);
    }

    public boolean checkClickQuota(Long tenantId) {
        return tenantRepository.findById(tenantId)
            .map(t -> {
                long used = getUsed(clickKey(tenantId));
                return used < t.getClickQuota();
            })
            .orElse(false);
    }

    public long incrementUrls(Long tenantId) {
        return increment(urlKey(tenantId));
    }

    public long incrementClicks(Long tenantId) {
        return increment(clickKey(tenantId));
    }

    public long getUrlsUsed(Long tenantId) {
        return getUsed(urlKey(tenantId));
    }

    public long getClicksUsed(Long tenantId) {
        return getUsed(clickKey(tenantId));
    }

    private long increment(String key) {
        Long val = redis.opsForValue().increment(key);
        // Set TTL to end of billing month if key is new
        if (val != null && val == 1L) {
            redis.expire(key, ttlToEndOfMonth());
        }
        return val == null ? 0L : val;
    }

    private long getUsed(String key) {
        String val = redis.opsForValue().get(key);
        return val == null ? 0L : Long.parseLong(val);
    }

    private String urlKey(Long tenantId)   { return "quota:" + tenantId + ":urls"; }
    private String clickKey(Long tenantId) { return "quota:" + tenantId + ":clicks"; }

    private Duration ttlToEndOfMonth() {
        LocalDate now      = LocalDate.now();
        LocalDate endMonth = now.with(TemporalAdjusters.lastDayOfMonth());
        long days = endMonth.toEpochDay() - now.toEpochDay() + 1;
        return Duration.ofDays(days);
    }

    /**
     * Every 60 seconds: read each active tenant's Redis quota counters and
     * persist the current values into the tenants table.
     *
     * Purpose: ensures billing data survives a Redis flush/restart. MySQL rows
     * are the source-of-truth for end-of-month billing; Redis is the live cache.
     */
    @Scheduled(fixedDelay = 60_000)
    public void syncQuotaToMySQL() {
        try {
            tenantRepository.findAllByActiveTrue().forEach(tenant -> {
                try {
                    long urlCount   = getUsed(urlKey(tenant.getId()));
                    long clickCount = getUsed(clickKey(tenant.getId()));
                    tenant.setUrlCount(urlCount);
                    tenant.setClickCount(clickCount);
                    tenantRepository.save(tenant);
                    log.debug("Synced quota to MySQL: tenantId={} urls={} clicks={}",
                        tenant.getId(), urlCount, clickCount);
                } catch (Exception e) {
                    log.warn("Failed to sync quota for tenantId={}: {}", tenant.getId(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("Quota sync scheduler error: {}", e.getMessage());
        }
    }
}
