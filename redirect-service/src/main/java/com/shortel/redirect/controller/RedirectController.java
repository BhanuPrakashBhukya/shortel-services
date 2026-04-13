package com.shortel.redirect.controller;

import com.shortel.redirect.dto.VerifyPasswordRequest;
import com.shortel.redirect.entity.ShortenedUrl;
import com.shortel.redirect.service.AclCacheService;
import com.shortel.redirect.service.ClickEventProducer;
import com.shortel.redirect.service.RedirectJwtService;
import com.shortel.redirect.service.RedirectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class RedirectController {

    private final RedirectService     redirectService;
    private final ClickEventProducer  clickEventProducer;
    private final AclCacheService     aclCacheService;
    private final RedirectJwtService  redirectJwtService;
    private final BCryptPasswordEncoder passwordEncoder;

    // ── Core redirect endpoint — the hot path ────────────────────────────────

    /**
     * Access control order (spec §8.3, §8.5):
     *   1. 404  — URL not found or inactive
     *   2. 410  — URL has expired
     *   3. Password check — if URL has a passwordHash:
     *        a. Valid X-Redirect-Token  → skip (user already verified password)
     *        b. No / invalid token      → 401 with X-Password-Required header
     *        c. Token wrong for code    → 401
     *   4. Private ACL check — if visibility = PRIVATE:
     *        a. No X-User-Id            → 401
     *        b. User not in ACL         → 403
     *   5. Async click event + 301 (PUBLIC) or 302 (PRIVATE) redirect
     */
    @GetMapping("/{code:[A-Za-z0-9]{4,10}}")
    public ResponseEntity<Object> redirect(
        @PathVariable String code,
        @RequestHeader(value = "X-User-Id",        required = false) String userIdHeader,
        @RequestHeader(value = "X-Url-Password",   required = false) String urlPassword,
        @RequestHeader(value = "X-Redirect-Token", required = false) String redirectToken,
        HttpServletRequest request
    ) {
        var resolved = redirectService.resolve(code);
        if (resolved.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RedirectService.ResolvedUrl r = resolved.get();

        if (r.expired()) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        // ── Password-protected URL check ──────────────────────────────────────
        if (r.passwordHash() != null) {
            // A valid redirect token proves the caller already passed verification
            boolean tokenOk = redirectToken != null
                && redirectJwtService.validateRedirectToken(redirectToken, code);

            if (!tokenOk) {
                if (urlPassword == null || urlPassword.isBlank()) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .header("X-Password-Required", "true")
                        .body(Map.of(
                            "error", "This URL is password-protected.",
                            "hint",  "POST /urls/" + code + "/verify with {\"password\":\"...\"} to obtain a redirect token."
                        ));
                }
                if (!passwordEncoder.matches(urlPassword, r.passwordHash())) {
                    log.debug("Incorrect password attempt for code={}", code);
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Incorrect password"));
                }
            }
        }

        // ── Private URL ACL check ─────────────────────────────────────────────
        if (r.visibility() == ShortenedUrl.Visibility.PRIVATE) {
            if (userIdHeader == null || userIdHeader.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required to access this private URL"));
            }
            Long userId;
            try {
                userId = Long.parseLong(userIdHeader);
            } catch (NumberFormatException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Invalid X-User-Id header"));
            }
            if (r.urlId() != null && !aclCacheService.hasAccess(r.urlId(), userId)) {
                log.debug("Access denied: user={} not in ACL for urlId={}", userId, r.urlId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You do not have access to this private URL"));
            }
        }

        // Fire-and-forget click event — never blocks the redirect
        clickEventProducer.publish(code, r.urlId(), r.tenantId(), request);

        int statusCode = (r.visibility() == ShortenedUrl.Visibility.PUBLIC) ? 301 : 302;
        return ResponseEntity.status(statusCode)
            .header(HttpHeaders.LOCATION, r.longUrl())
            .build();
    }

    // ── Password verify endpoint (spec §8.5) ─────────────────────────────────

    /**
     * Verifies the password for a password-protected URL.
     * On success returns a short-lived redirect token (5-min JWT).
     * The caller should include this token as X-Redirect-Token on subsequent
     * redirect requests to avoid re-entering the password within the session.
     */
    @PostMapping("/{code:[A-Za-z0-9]{4,10}}/verify")
    public ResponseEntity<Map<String, Object>> verifyPassword(
        @PathVariable String code,
        @Valid @RequestBody VerifyPasswordRequest request
    ) {
        var resolved = redirectService.resolve(code);
        if (resolved.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RedirectService.ResolvedUrl r = resolved.get();

        if (r.expired()) {
            return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("error", "This URL has expired"));
        }

        if (r.passwordHash() == null) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "This URL is not password-protected"));
        }

        if (!passwordEncoder.matches(request.getPassword(), r.passwordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Incorrect password"));
        }

        String token = redirectJwtService.issueRedirectToken(code);
        return ResponseEntity.ok(Map.of(
            "redirectToken", token,
            "expiresIn",     300,
            "hint",          "Include as X-Redirect-Token header when requesting /" + code
        ));
    }

    // ── Info endpoint (no redirect) ───────────────────────────────────────────

    @GetMapping("/resolve/{code}")
    public ResponseEntity<Map<String, Object>> resolveInfo(@PathVariable String code) {
        var resolved = redirectService.resolve(code);
        if (resolved.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        RedirectService.ResolvedUrl r = resolved.get();
        return ResponseEntity.ok(Map.of(
            "shortCode",         code,
            "longUrl",           r.longUrl(),
            "visibility",        r.visibility().name(),
            "expired",           r.expired(),
            "passwordProtected", r.passwordHash() != null
        ));
    }
}
