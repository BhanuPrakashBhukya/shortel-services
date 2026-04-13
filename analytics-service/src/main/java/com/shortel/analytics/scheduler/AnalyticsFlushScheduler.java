package com.shortel.analytics.scheduler;

import com.shortel.analytics.repository.AnalyticsHourlyRepository;
import com.shortel.analytics.service.AnalyticsCounterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Micro-batch flush: every 5 seconds, atomically reset Redis counters
 * and upsert totals into analytics_hourly MySQL table.
 *
 * Uses SCAN (cursor-based, O(1) per step) instead of KEYS (O(N), blocks Redis)
 * to enumerate active URL counter keys.
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
        List<String> keys = scanClickKeys();
        if (keys.isEmpty()) return;

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

    /**
     * Enumerate all {@code clicks:*} keys using SCAN cursor iteration.
     * Unlike KEYS, SCAN is non-blocking: it returns a cursor-based page of
     * results (count=100 is a hint to Redis, not a hard limit) and does not
     * lock the server for the duration of the scan.
     */
    private List<String> scanClickKeys() {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match("clicks:*").count(100).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            cursor.forEachRemaining(keys::add);
        } catch (Exception e) {
            log.warn("SCAN for click keys failed: {}", e.getMessage());
        }
        return keys;
    }
}
