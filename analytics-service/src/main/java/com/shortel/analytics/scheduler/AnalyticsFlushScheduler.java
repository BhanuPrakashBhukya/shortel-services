package com.shortel.analytics.scheduler;

import com.shortel.analytics.repository.AnalyticsHourlyRepository;
import com.shortel.analytics.service.AnalyticsCounterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Micro-batch flush: every 5 seconds, atomically reset Redis counters
 * and upsert totals into analytics_hourly MySQL table.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsFlushScheduler {

    private final StringRedisTemplate redis;
    private final AnalyticsCounterService counterService;
    private final AnalyticsHourlyRepository analyticsRepository;

    @Scheduled(fixedDelay = 5_000)
    public void flush() {
        Set<String> keys = redis.keys("clicks:*");
        if (keys == null || keys.isEmpty()) return;

        LocalDateTime hourBucket = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);

        for (String key : keys) {
            try {
                Long urlId = Long.parseLong(key.replace("clicks:", ""));
                long clicks = counterService.getAndResetClickCount(urlId);
                if (clicks <= 0) continue;

                long uniques = counterService.getUniqueCount(urlId);
                analyticsRepository.upsert(urlId, hourBucket, clicks, uniques);
                log.debug("Flushed analytics: urlId={} clicks={} uniques={}", urlId, clicks, uniques);
            } catch (Exception e) {
                log.warn("Failed to flush analytics for key {}: {}", key, e.getMessage());
            }
        }
    }
}
