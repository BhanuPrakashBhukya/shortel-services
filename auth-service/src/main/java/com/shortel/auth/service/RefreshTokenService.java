package com.shortel.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Manages refresh tokens in Redis.
 * Key: session:{refreshToken}  →  Value: userId
 * TTL: 7 days (configurable)
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String KEY_PREFIX = "session:";

    private final StringRedisTemplate redis;

    @Value("${jwt.refresh-token-ttl-days:7}")
    private long refreshTtlDays;

    public void store(String refreshToken, Long userId) {
        redis.opsForValue().set(
            KEY_PREFIX + refreshToken,
            String.valueOf(userId),
            Duration.ofDays(refreshTtlDays)
        );
    }

    public Optional<Long> getUserId(String refreshToken) {
        String value = redis.opsForValue().get(KEY_PREFIX + refreshToken);
        return Optional.ofNullable(value).map(Long::parseLong);
    }

    public void revoke(String refreshToken) {
        redis.delete(KEY_PREFIX + refreshToken);
    }
}
