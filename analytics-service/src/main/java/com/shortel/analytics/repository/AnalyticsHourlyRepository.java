package com.shortel.analytics.repository;

import com.shortel.analytics.entity.AnalyticsHourly;
import com.shortel.analytics.entity.AnalyticsHourlyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface AnalyticsHourlyRepository extends JpaRepository<AnalyticsHourly, AnalyticsHourlyId> {

    List<AnalyticsHourly> findByUrlIdAndHourBucketBetweenOrderByHourBucketDesc(
        Long urlId, LocalDateTime from, LocalDateTime to);

    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO analytics_hourly (url_id, hour_bucket, click_count, unique_count)
        VALUES (:urlId, :hourBucket, :clicks, :uniques)
        ON DUPLICATE KEY UPDATE
          click_count  = click_count  + VALUES(click_count),
          unique_count = unique_count + VALUES(unique_count)
        """, nativeQuery = true)
    void upsert(@Param("urlId") Long urlId, @Param("hourBucket") LocalDateTime hourBucket,
               @Param("clicks") long clicks, @Param("uniques") long uniques);
}
