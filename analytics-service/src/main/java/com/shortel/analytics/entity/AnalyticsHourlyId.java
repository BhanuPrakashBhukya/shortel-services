package com.shortel.analytics.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class AnalyticsHourlyId implements Serializable {
    private Long urlId;
    private LocalDateTime hourBucket;
}
