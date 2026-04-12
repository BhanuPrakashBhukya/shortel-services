package com.shortel.auth.controller;

import com.shortel.auth.dto.LoginRequest;
import com.shortel.auth.dto.RefreshRequest;
import com.shortel.auth.dto.TokenResponse;
import com.shortel.auth.service.JwtService;
import com.shortel.auth.service.RefreshTokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
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

    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    /**
     * POST /auth/token
     * Phase 1: accepts a mock user. Production: validates against user store / OAuth2 provider.
     */
    @PostMapping("/token")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest request) {
        // Phase 1: accept any user with non-empty email & password, assign userId=1
        if (request.getEmail() == null || request.getPassword() == null) {
            return ResponseEntity.badRequest().build();
        }

        Long userId = 1L; // TODO: lookup from user store
        String role  = "USER";

        String accessToken  = jwtService.generateAccessToken(userId, request.getEmail(), role);
        String refreshToken = jwtService.generateRefreshToken();
        refreshTokenService.store(refreshToken, userId);

        log.info("Issued token for email={}", request.getEmail());
        return ResponseEntity.ok(new TokenResponse(accessToken, refreshToken, "Bearer", 900));
    }

    /**
     * POST /auth/refresh — exchange refresh token for new access token
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshRequest request) {
        return refreshTokenService.getUserId(request.getRefreshToken())
            .map(userId -> {
                String newAccess = jwtService.generateAccessToken(userId, "user@shortel.io", "USER");
                return ResponseEntity.ok(Map.of("accessToken", newAccess, "tokenType", "Bearer", "expiresIn", 900));
            })
            .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    /**
     * POST /auth/logout — revoke refresh token
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshRequest request) {
        refreshTokenService.revoke(request.getRefreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /auth/validate — validate access token, returns claims (used by gateway)
     */
    @GetMapping("/validate")
    public ResponseEntity<?> validate(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            Claims claims = jwtService.validateAndParse(authHeader.substring(7));
            return ResponseEntity.ok(Map.of(
                "userId", claims.getSubject(),
                "email",  claims.get("email", String.class),
                "role",   claims.get("role", String.class)
            ));
        } catch (JwtException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        }
    }
}
