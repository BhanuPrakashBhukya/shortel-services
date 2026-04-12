package com.shortel.auth.controller;

import com.shortel.auth.dto.LoginRequest;
import com.shortel.auth.dto.RefreshRequest;
import com.shortel.auth.dto.RegisterRequest;
import com.shortel.auth.dto.TokenResponse;
import com.shortel.auth.entity.User;
import com.shortel.auth.service.JwtService;
import com.shortel.auth.service.RefreshTokenService;
import com.shortel.auth.service.UserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtService          jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserService         userService;

    // ── POST /auth/register ─────────────────────────────────────────────────

    /**
     * Register a new user account.
     * Returns basic user info — caller must POST /auth/token to obtain a JWT.
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(
        @Valid @RequestBody RegisterRequest request
    ) {
        Long tenantId = request.getTenantId() != null ? request.getTenantId() : 1L;
        User user = userService.register(
            request.getEmail(), request.getPassword(), request.getName(), tenantId);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "id",       user.getId(),
            "email",    user.getEmail(),
            "name",     user.getName() != null ? user.getName() : "",
            "role",     user.getRole(),
            "tenantId", tenantId
        ));
    }

    // ── POST /auth/token ────────────────────────────────────────────────────

    /**
     * Login with email + password.
     * Returns RS256-signed access token (15-min) and refresh token (7-day, Redis-backed).
     */
    @PostMapping("/token")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest request) {
        if (request.getEmail() == null || request.getPassword() == null) {
            return ResponseEntity.badRequest().build();
        }

        User user = userService.authenticate(request.getEmail(), request.getPassword());

        String accessToken  = jwtService.generateAccessToken(
            user.getId(), user.getEmail(), user.getRole(), user.getTenantId());
        String refreshToken = jwtService.generateRefreshToken();
        refreshTokenService.store(refreshToken, user.getId());

        log.info("Token issued: userId={} email={}", user.getId(), user.getEmail());
        return ResponseEntity.ok(
            new TokenResponse(accessToken, refreshToken, "Bearer", 900));
    }

    // ── POST /auth/refresh ──────────────────────────────────────────────────

    /**
     * Exchange a refresh token for a new access token.
     * Looks up the user from DB to get current email + role (handles role changes).
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshRequest request) {
        return refreshTokenService.getUserId(request.getRefreshToken())
            .flatMap(userService::findById)
            .map(user -> {
                String newAccess = jwtService.generateAccessToken(
                    user.getId(), user.getEmail(), user.getRole(), user.getTenantId());
                return ResponseEntity.ok(Map.of(
                    "accessToken", newAccess,
                    "tokenType",   "Bearer",
                    "expiresIn",   900
                ));
            })
            .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    // ── POST /auth/logout ───────────────────────────────────────────────────

    /**
     * Revoke refresh token.
     * Access token expires naturally (15-min TTL — no blacklist needed).
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshRequest request) {
        refreshTokenService.revoke(request.getRefreshToken());
        return ResponseEntity.noContent().build();
    }

    // ── GET /auth/validate ──────────────────────────────────────────────────

    /**
     * Validate an access token and return its claims.
     * Used by clients for token introspection.
     * The Gateway validates inline — this endpoint is for clients only.
     */
    @GetMapping("/validate")
    public ResponseEntity<?> validate(
        @RequestHeader("Authorization") String authHeader
    ) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            Claims claims = jwtService.validateAndParse(authHeader.substring(7));
            var body = Map.of(
                "userId",   claims.getSubject(),
                "email",    claims.get("email",    String.class),
                "role",     claims.get("role",     String.class),
                "tenantId", claims.get("tenantId", Object.class)
            );
            return ResponseEntity.ok(body);
        } catch (JwtException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", e.getMessage()));
        }
    }
}
