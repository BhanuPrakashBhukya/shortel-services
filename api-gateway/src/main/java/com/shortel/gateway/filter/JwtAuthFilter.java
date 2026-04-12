package com.shortel.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
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

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Global JWT validation filter (RS256).
 *
 * Public paths are allowed through without a token.
 * On a valid JWT the filter injects trusted headers for downstream services:
 *   X-User-Id      → JWT sub claim (userId)
 *   X-User-Role    → role claim
 *   X-User-Email   → email claim
 *   X-Tenant-Id    → tenantId claim (if present)
 */
@Slf4j
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final List<String> PUBLIC_PREFIXES = List.of(
        "/auth/", "/actuator/", "/resolve/"
    );
    // 4-10 alphanumeric chars directly at root — short-code redirect (public)
    private static final String SHORT_CODE_PATTERN = "^/[A-Za-z0-9]{4,10}$";

    private final PublicKey publicKey;

    public JwtAuthFilter(@Value("${jwt.rsa.public-key}") String publicKeyBase64) throws Exception {
        this.publicKey = loadPublicKey(publicKeyBase64);
        log.info("JwtAuthFilter initialised — RS256 public key loaded");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

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
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(authHeader.substring(7))
                .getPayload();

            var requestBuilder = exchange.getRequest().mutate()
                .header("X-User-Id",    claims.getSubject())
                .header("X-User-Role",  claims.get("role",     String.class))
                .header("X-User-Email", claims.get("email",    String.class));

            // Inject tenant ID if present in token
            Object tenantId = claims.get("tenantId");
            if (tenantId != null) {
                requestBuilder.header("X-Tenant-Id", tenantId.toString());
            }

            return chain.filter(exchange.mutate().request(requestBuilder.build()).build());

        } catch (JwtException e) {
            log.debug("JWT validation failed for {}: {}", path, e.getMessage());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    private boolean isPublic(String path) {
        if (path.matches(SHORT_CODE_PATTERN)) return true;
        return PUBLIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    public int getOrder() {
        return -100; // runs before all route filters
    }

    private PublicKey loadPublicKey(String base64) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(base64.replaceAll("\\s+", ""));
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
    }
}
