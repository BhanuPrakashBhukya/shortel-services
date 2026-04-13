package com.shortel.url.repository;

import com.shortel.url.entity.ShortenedUrl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UrlRepository extends JpaRepository<ShortenedUrl, Long> {
    Optional<ShortenedUrl> findByShortCodeAndActiveTrue(String shortCode);
    List<ShortenedUrl> findByTenantIdAndActiveTrueOrderByCreatedAtDesc(Long tenantId);
    List<ShortenedUrl> findByTenantIdAndCreatedByAndActiveTrueOrderByCreatedAtDesc(Long tenantId, Long createdBy);
    boolean existsByShortCode(String shortCode);

    /** Used by the expiry sweeper to find URLs whose expiresAt has passed and are still active. */
    @Query("SELECT u FROM ShortenedUrl u WHERE u.expiresAt < :now AND u.active = true ORDER BY u.expiresAt ASC")
    List<ShortenedUrl> findExpiredActive(@Param("now") LocalDateTime now, Pageable pageable);
}
