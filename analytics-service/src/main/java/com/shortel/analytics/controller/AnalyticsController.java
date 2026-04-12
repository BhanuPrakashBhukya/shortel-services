package com.shortel.analytics.controller;

import com.shortel.analytics.repository.AnalyticsHourlyRepository;
import com.shortel.analytics.service.AnalyticsCounterService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsCounterService counterService;
    private final AnalyticsHourlyRepository analyticsRepository;

    /**
     * GET /api/v1/analytics/{urlId}/stats — live real-time stats from Redis
     */
    @GetMapping("/{urlId}/stats")
    public ResponseEntity<Map<String, Object>> liveStats(@PathVariable Long urlId) {
        return ResponseEntity.ok(Map.of(
            "urlId",       urlId,
            "clicks",      counterService.getClickCount(urlId),
            "uniqueVisitors", counterService.getUniqueCount(urlId),
            "source",      "redis-realtime"
        ));
    }

    /**
     * GET /api/v1/analytics/{urlId}?from=...&to=... — historical from MySQL
     */
    @GetMapping("/{urlId}")
    public ResponseEntity<?> history(
        @PathVariable Long urlId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to
    ) {
        LocalDateTime start = from != null ? from : LocalDateTime.now().minusDays(7);
        LocalDateTime end   = to   != null ? to   : LocalDateTime.now();

        var records = analyticsRepository.findByUrlIdAndHourBucketBetweenOrderByHourBucketDesc(urlId, start, end);
        long totalClicks  = records.stream().mapToLong(r -> r.getClickCount()).sum();
        long totalUniques = records.stream().mapToLong(r -> r.getUniqueCount()).sum();

        return ResponseEntity.ok(Map.of(
            "urlId",        urlId,
            "from",         start.toString(),
            "to",           end.toString(),
            "totalClicks",  totalClicks,
            "totalUniques", totalUniques,
            "hourlyBuckets", records
        ));
    }
}
