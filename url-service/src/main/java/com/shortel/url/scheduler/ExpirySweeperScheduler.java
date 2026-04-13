package com.shortel.url.scheduler;

import com.shortel.url.entity.ShortenedUrl;
import com.shortel.url.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Daily cron job that soft-deactivates URLs whose {@code expires_at} has passed
 * (spec §5.2 — "expiry sweeper" index, §8.4 — "daily sweeper job").
 *
 * Runs at 02:00 AM server time. Processes in batches of 1000 to avoid
 * holding a long-running transaction or locking many rows at once.
 *
 * For each expired URL:
 *   1. Sets is_active = false in MySQL
 *   2. Deletes the Redis cache key so the redirect service doesn't serve stale data
 *   3. Publishes a URL_DEACTIVATED event so Caffeine L1 caches are invalidated
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpirySweeperScheduler {

    private static final String CACHE_PREFIX = "url:";
    private static final int    BATCH_SIZE   = 1000;

    private final UrlRepository                  urlRepository;
    private final StringRedisTemplate            redis;
    private final KafkaTemplate<String, String>  kafkaTemplate;

    @Scheduled(cron = "0 0 2 * * *")  // 02:00 AM daily
    public void sweepExpiredUrls() {
        LocalDateTime now  = LocalDateTime.now();
        int deactivated    = 0;
        List<ShortenedUrl> batch;

        log.info("Expiry sweeper started at {}", now);
        do {
            batch = urlRepository.findExpiredActive(now, PageRequest.of(0, BATCH_SIZE));
            for (ShortenedUrl url : batch) {
                try {
                    url.setActive(false);
                    urlRepository.save(url);

                    // Invalidate Redis L2 cache
                    redis.delete(CACHE_PREFIX + url.getShortCode());

                    // Broadcast invalidation event for Caffeine L1 caches
                    publishDeactivatedEvent(url);

                    deactivated++;
                } catch (Exception e) {
                    log.warn("Failed to deactivate expired URL code={}: {}", url.getShortCode(), e.getMessage());
                }
            }
        } while (batch.size() == BATCH_SIZE);

        if (deactivated > 0) {
            log.info("Expiry sweeper finished — deactivated {} URLs", deactivated);
        } else {
            log.debug("Expiry sweeper: no expired URLs found");
        }
    }

    private void publishDeactivatedEvent(ShortenedUrl url) {
        try {
            String event = String.format(
                "{\"event\":\"URL_DEACTIVATED\",\"shortCode\":\"%s\",\"urlId\":%d,\"tenantId\":%d,\"timestamp\":\"%s\"}",
                url.getShortCode(), url.getId(), url.getTenantId(), LocalDateTime.now()
            );
            kafkaTemplate.send("shortel.url-events", url.getShortCode(), event);
        } catch (Exception e) {
            log.warn("Failed to publish URL_DEACTIVATED event for code={}: {}", url.getShortCode(), e.getMessage());
        }
    }
}
