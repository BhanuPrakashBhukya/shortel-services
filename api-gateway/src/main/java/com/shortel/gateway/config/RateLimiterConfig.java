package com.shortel.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Redis token-bucket rate limiter (spec §8.1).
 *
 * Two rate limiter profiles:
 *  - createRateLimiter  : 10 req/s per key (URL creation — write path)
 *  - redirectRateLimiter: 100 req/s per key (redirect hot path)
 *
 * Key resolver precedence:
 *  1. X-Tenant-Id header (injected by JwtAuthFilter for authenticated requests)
 *  2. X-Forwarded-For header (behind ALB/proxy)
 *  3. Remote address (direct connection)
 */
@Configuration
public class RateLimiterConfig {

    /**
     * Rate limiter for write-path routes (URL creation, tenant management).
     * replenishRate=10, burstCapacity=20 — allows bursts up to 20 while
     * sustaining 10 req/s average.
     */
    @Bean
    public RedisRateLimiter createRateLimiter() {
        return new RedisRateLimiter(10, 20, 1);
    }

    /**
     * Rate limiter for the redirect hot path.
     * replenishRate=100, burstCapacity=200 — handles traffic spikes.
     */
    @Bean
    public RedisRateLimiter redirectRateLimiter() {
        return new RedisRateLimiter(100, 200, 1);
    }

    /**
     * Rate limiter for auth endpoints.
     * Tighter limits to prevent brute-force credential stuffing.
     * replenishRate=5, burstCapacity=10.
     */
    @Bean
    public RedisRateLimiter authRateLimiter() {
        return new RedisRateLimiter(5, 10, 1);
    }

    /**
     * Key resolver: tenant ID (authenticated) → forwarded IP → remote IP.
     * Allows per-tenant rate limiting when the user is authenticated.
     */
    @Bean
    public KeyResolver rateLimitKeyResolver() {
        return exchange -> {
            // Authenticated requests: rate-limit per tenant
            String tenantId = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
            if (tenantId != null && !tenantId.isBlank()) {
                return Mono.just("tenant:" + tenantId);
            }

            // Behind load balancer: use X-Forwarded-For
            String forwarded = exchange.getRequest().getHeaders()
                .getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // X-Forwarded-For may contain a comma-separated list; take the first IP
                return Mono.just("ip:" + forwarded.split(",")[0].trim());
            }

            // Direct connection
            var remoteAddress = exchange.getRequest().getRemoteAddress();
            if (remoteAddress != null) {
                return Mono.just("ip:" + remoteAddress.getAddress().getHostAddress());
            }

            return Mono.just("unknown");
        };
    }
}
