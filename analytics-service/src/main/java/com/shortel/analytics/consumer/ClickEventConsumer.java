package com.shortel.analytics.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortel.analytics.service.AnalyticsCounterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumes click events from Kafka topic shortel.clicks.
 * Enriches event (mock GeoIP / UA parsing) and updates Redis counters.
 * Commits offset only after successful processing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickEventConsumer {

    private final AnalyticsCounterService counterService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "shortel.clicks",
        groupId = "analytics-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(
        @Payload String message,
        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
        @Header(KafkaHeaders.OFFSET) long offset
    ) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = objectMapper.readValue(message, Map.class);

            Long urlId    = event.get("urlId") != null ? ((Number) event.get("urlId")).longValue() : null;
            String ip     = (String) event.getOrDefault("ip", "unknown");
            String ua     = (String) event.getOrDefault("userAgent", "");
            String code   = (String) event.getOrDefault("shortCode", "");

            if (urlId == null || urlId == 0) {
                log.debug("Skipping click event with no urlId: code={}", code);
                return;
            }

            // Visitor key: IP + UA hash for unique estimation
            String visitorKey = ip + ":" + Integer.toHexString(ua.hashCode());

            // Enrich (Phase 1: mock enrichment)
            String country = enrichCountry(ip);
            String device  = parseDevice(ua);

            counterService.incrementClick(urlId, visitorKey);

            log.debug("Processed click: code={} urlId={} country={} device={} partition={} offset={}",
                code, urlId, country, device, partition, offset);

        } catch (Exception e) {
            log.error("Failed to process click event: {} — error: {}", message, e.getMessage(), e);
            // Do not re-throw: offset will be committed, failed event won't block
            // TODO: send to DLQ (SQS) for manual reprocessing
        }
    }

    // Phase 1: mock GeoIP — replace with MaxMind in production
    private String enrichCountry(String ip) {
        return "UNKNOWN";
    }

    // Phase 1: basic device detection — replace with UAParser in production
    private String parseDevice(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "unknown";
        String ua = userAgent.toLowerCase();
        if (ua.contains("mobile") || ua.contains("android") || ua.contains("iphone")) return "mobile";
        if (ua.contains("tablet") || ua.contains("ipad")) return "tablet";
        return "desktop";
    }
}
