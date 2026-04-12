package com.shortel.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Global JWT validation filter.
 * Public paths (auth/**, redirect) are excluded.
 * On valid JWT: injects X-User-Id and X-User-Role headers for downstream services.
 */
@Slf4j
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final List<String> PUBLIC_PATHS = List.of(
        "/auth/", "/actuator/", "/r/", "/resolve/"
    );
    // Short code pattern — 4-10 alphanumeric chars directly on the root path
    private static final String SHORT_CODE_PATTERN = "^/[A-Za-z0-9]{4,10}$";

    private final SecretKey signingKey;

    public JwtAuthFilter(@Value("${jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Allow public paths without JWT
        if (isPublic(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        try {
            Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(authHeader.substring(7))
                .getPayload();

            // Inject trusted headers for downstream services
            ServerWebExchange mutated = exchange.mutate()
                .request(r -> r
                    .header("X-User-Id",   claims.getSubject())
                    .header("X-User-Role", claims.get("role", String.class))
                    .header("X-User-Email", claims.get("email", String.class))
                )
                .build();

            return chain.filter(mutated);

        } catch (JwtException e) {
            log.debug("JWT validation failed for path {}: {}", path, e.getMessage());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    private boolean isPublic(String path) {
        if (path.matches(SHORT_CODE_PATTERN)) return true;
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    public int getOrder() {
        return -100; // Run before route filters
    }
}
