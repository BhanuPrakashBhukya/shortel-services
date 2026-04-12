package com.shortel.analytics.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "analytics_hourly")
@Data
@NoArgsConstructor
@IdClass(AnalyticsHourlyId.class)
public class AnalyticsHourly {

    @Id
    @Column(name = "url_id")
    private Long urlId;

    @Id
    @Column(name = "hour_bucket")
    private LocalDateTime hourBucket;

    @Column(name = "click_count")
    private long clickCount = 0;

    @Column(name = "unique_count")
    private long uniqueCount = 0;

    public AnalyticsHourly(Long urlId, LocalDateTime hourBucket) {
        this.urlId = urlId;
        this.hourBucket = hourBucket;
    }
}
