package com.shortel.redirect.repository;

import com.shortel.redirect.entity.ShortenedUrl;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UrlRepository extends JpaRepository<ShortenedUrl, Long> {
    Optional<ShortenedUrl> findByShortCodeAndActiveTrue(String shortCode);
}
