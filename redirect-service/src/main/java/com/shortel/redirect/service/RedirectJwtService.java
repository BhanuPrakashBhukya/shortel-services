package com.shortel.redirect.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;

/**
 * Issues and validates short-lived redirect tokens (spec §8.5).
 *
 * Flow:
 *   1. Client hits a password-protected URL → 401 with X-Password-Required header
 *   2. Client POSTs password to /urls/{code}/verify
 *   3. BCrypt check passes → this service issues a 5-min HMAC-SHA256 JWT
 *   4. Client re-requests /{code} with X-Redirect-Token header → no password re-entry needed
 *
 * The token is specific to the short code (sub = code) so it cannot be reused
 * for a different URL.
 */
@Slf4j
@Service
public class RedirectJwtService {

    private static final String TOKEN_TYPE_CLAIM = "type";
    private static final String TOKEN_TYPE_VALUE = "redirect_access";

    private final SecretKey secretKey;
    private final long      ttlMs;

    public RedirectJwtService(
        @Value("${redirect.token.secret}") String secret,
        @Value("${redirect.token.ttl-minutes:5}") long ttlMinutes
    ) {
        // Ensure key is at least 32 bytes (256 bits) for HMAC-SHA256
        byte[] raw  = secret.getBytes(StandardCharsets.UTF_8);
        byte[] key  = raw.length >= 32 ? raw : Arrays.copyOf(raw, 32);
        this.secretKey = Keys.hmacShaKeyFor(key);
        this.ttlMs     = ttlMinutes * 60 * 1000L;
        log.info("RedirectJwtService initialised — HMAC-SHA256, TTL {}m", ttlMinutes);
    }

    /**
     * Issues a redirect token proving the caller has successfully verified the
     * password for the given short code.
     */
    public String issueRedirectToken(String code) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + ttlMs);
        return Jwts.builder()
            .subject(code)
            .claim(TOKEN_TYPE_CLAIM, TOKEN_TYPE_VALUE)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(secretKey)
            .compact();
    }

    /**
     * Returns {@code true} if the token is valid, unexpired, and was issued
     * specifically for {@code code}.
     */
    public boolean validateRedirectToken(String token, String code) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
            return code.equals(claims.getSubject())
                && TOKEN_TYPE_VALUE.equals(claims.get(TOKEN_TYPE_CLAIM, String.class));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid redirect token for code={}: {}", code, e.getMessage());
            return false;
        }
    }
}
