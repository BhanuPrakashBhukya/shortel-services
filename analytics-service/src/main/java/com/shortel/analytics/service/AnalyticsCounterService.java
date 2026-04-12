package com.shortel.analytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed real-time counters.
 * clicks:{urlId}      → INCR for click count
 * hll:{urlId}         → PFADD for unique visitor estimate (HyperLogLog)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsCounterService {

    private final StringRedisTemplate redis;

    public void incrementClick(Long urlId, String visitorKey) {
        String clickKey = "clicks:" + urlId;
        Long count = redis.opsForValue().increment(clickKey);
        if (count != null && count == 1L) {
            redis.expire(clickKey, Duration.ofDays(2));
        }

        // HyperLogLog for unique visitor estimation
        String hllKey = "hll:" + urlId;
        redis.opsForHyperLogLog().add(hllKey, visitorKey);
    }

    public long getClickCount(Long urlId) {
        String val = redis.opsForValue().get("clicks:" + urlId);
        return val == null ? 0L : Long.parseLong(val);
    }

    public long getUniqueCount(Long urlId) {
        return redis.opsForHyperLogLog().size("hll:" + urlId);
    }

    /**
     * Atomically read and reset the click counter for a URL.
     * Used by the flush scheduler: read current value, reset to 0.
     */
    public long getAndResetClickCount(Long urlId) {
        String key = "clicks:" + urlId;
        String val = redis.opsForValue().getAndSet(key, "0");
        return val == null ? 0L : Long.parseLong(val);
    }
}
