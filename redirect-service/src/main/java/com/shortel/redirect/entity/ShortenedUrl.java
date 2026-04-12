package com.shortel.redirect.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "urls")
@Data
@NoArgsConstructor
public class ShortenedUrl {

    @Id
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "short_code")
    private String shortCode;

    @Column(name = "long_url", columnDefinition = "TEXT")
    private String longUrl;

    @Enumerated(EnumType.STRING)
    private Visibility visibility;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "is_active")
    private boolean active;

    public enum Visibility { PUBLIC, PRIVATE }

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }
}
