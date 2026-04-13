package com.shortel.tenant.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "tenants")
@Data
@NoArgsConstructor
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plan plan = Plan.FREE;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "is_active")
    private boolean active = true;

    /** Snapshot of current-month URL creations, synced from Redis every 60 s. */
    @Column(name = "url_count")
    private long urlCount = 0L;

    /** Snapshot of current-month click count, synced from Redis every 60 s. */
    @Column(name = "click_count")
    private long clickCount = 0L;

    public enum Plan { FREE, PAID }

    public long getUrlQuota() {
        return plan == Plan.FREE ? 100L : Long.MAX_VALUE;
    }

    public long getClickQuota() {
        return plan == Plan.FREE ? 10_000L : Long.MAX_VALUE;
    }
}
