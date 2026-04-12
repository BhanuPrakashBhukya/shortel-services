package com.shortel.redirect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Fire-and-forget click event publisher.
 * Published AFTER the HTTP response is sent — never blocks the redirect path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickEventProducer {

    private static final String TOPIC = "shortel.clicks";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Async
    public void publish(String shortCode, Long urlId, Long tenantId, HttpServletRequest request) {
        try {
            String event = objectMapper.writeValueAsString(Map.of(
                "urlId",     urlId != null ? urlId : 0L,
                "shortCode", shortCode,
                "tenantId",  tenantId != null ? tenantId : 0L,
                "timestamp", Instant.now().toEpochMilli(),
                "ip",        getClientIp(request),
                "userAgent", request.getHeader("User-Agent") != null ? request.getHeader("User-Agent") : "",
                "referrer",  request.getHeader("Referer") != null ? request.getHeader("Referer") : ""
            ));
            kafkaTemplate.send(TOPIC, shortCode, event);
        } catch (Exception e) {
            log.warn("Failed to publish click event for {}: {}", shortCode, e.getMessage());
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
