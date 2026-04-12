package com.shortel.url.repository;

import com.shortel.url.entity.ShortenedUrl;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UrlRepository extends JpaRepository<ShortenedUrl, Long> {
    Optional<ShortenedUrl> findByShortCodeAndActiveTrue(String shortCode);
    List<ShortenedUrl> findByTenantIdAndActiveTrueOrderByCreatedAtDesc(Long tenantId);
    boolean existsByShortCode(String shortCode);
}
