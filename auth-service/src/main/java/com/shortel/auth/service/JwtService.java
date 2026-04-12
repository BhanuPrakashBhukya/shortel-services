package com.shortel.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * JWT service using RS256 (RSA 2048-bit).
 * Auth-service holds the private key for signing.
 * The public key is shared with the API Gateway for verification.
 * Auth-service derives the public key from the private key at startup.
 */
@Slf4j
@Service
public class JwtService {

    private final PrivateKey privateKey;
    private final PublicKey  publicKey;
    private final long       accessTokenTtlMs;

    public JwtService(
        @Value("${jwt.rsa.private-key}") String privateKeyBase64,
        @Value("${jwt.access-token-ttl-minutes:15}") long ttlMinutes
    ) throws Exception {
        this.privateKey       = loadPrivateKey(privateKeyBase64);
        this.publicKey        = derivePublicKey(this.privateKey);
        this.accessTokenTtlMs = ttlMinutes * 60 * 1000L;
        log.info("JwtService initialised — RS256, access TTL {}m", ttlMinutes);
    }

    // ── Token generation ────────────────────────────────────────────────────

    public String generateAccessToken(Long userId, String email, String role, Long tenantId) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + accessTokenTtlMs);

        var builder = Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("email",    email)
            .claim("role",     role)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(privateKey);

        if (tenantId != null) {
            builder.claim("tenantId", tenantId);
        }
        return builder.compact();
    }

    /** UUID refresh token — actual session data stored in Redis. */
    public String generateRefreshToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ── Token validation ─────────────────────────────────────────────────────

    public Claims validateAndParse(String token) {
        return Jwts.parser()
            .verifyWith(publicKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    // ── Key loading helpers ──────────────────────────────────────────────────

    private PrivateKey loadPrivateKey(String base64) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(base64.replaceAll("\\s+", ""));
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    /**
     * Derive the RSA public key from the private key (CRT parameters).
     * Avoids storing the public key separately in auth-service.
     */
    private PublicKey derivePublicKey(PrivateKey privateKey) throws Exception {
        RSAPrivateCrtKey crtKey = (RSAPrivateCrtKey) privateKey;
        RSAPublicKeySpec spec   = new RSAPublicKeySpec(
            crtKey.getModulus(), crtKey.getPublicExponent());
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }
}
