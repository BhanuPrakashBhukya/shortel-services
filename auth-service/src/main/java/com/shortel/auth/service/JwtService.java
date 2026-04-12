package com.shortel.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTokenTtlMs;

    public JwtService(
        @Value("${jwt.secret}") String secret,
        @Value("${jwt.access-token-ttl-minutes:15}") long ttlMinutes
    ) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtlMs = ttlMinutes * 60 * 1000L;
    }

    public String generateAccessToken(Long userId, String email, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenTtlMs);

        return Jwts.builder()
            .subject(String.valueOf(userId))
            .claims(Map.of("email", email, "role", role))
            .issuedAt(now)
            .expiration(expiry)
            .signWith(signingKey)
            .compact();
    }

    public Claims validateAndParse(String token) {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public String generateRefreshToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
