package com.shortel.redirect.controller;

import com.shortel.redirect.entity.ShortenedUrl;
import com.shortel.redirect.repository.UrlAccessListRepository;
import com.shortel.redirect.service.ClickEventProducer;
import com.shortel.redirect.service.RedirectService;
import jakarta.servlet.http.HttpServletRequest;
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

    private final RedirectService         redirectService;
    private final ClickEventProducer      clickEventProducer;
    private final UrlAccessListRepository urlAccessListRepository;
    private final BCryptPasswordEncoder   passwordEncoder;

    /**
     * Core redirect endpoint — the hot path.
     *
     * Access control order:
     *   1. 404 if URL not found or inactive
     *   2. 410 if URL has expired
     *   3. 401 if URL is password-protected and no/wrong password supplied
     *   4. 401/403 if URL is PRIVATE and caller is not in the access list
     *   5. Async click event + 301/302 redirect
     */
    @GetMapping("/{code:[A-Za-z0-9]{4,10}}")
    public ResponseEntity<Object> redirect(
        @PathVariable String code,
        @RequestHeader(value = "X-User-Id",       required = false) String userIdHeader,
        @RequestHeader(value = "X-Url-Password",  required = false) String urlPassword,
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
            if (urlPassword == null || urlPassword.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("X-Password-Required", "true")
                    .body(Map.of("error", "This URL is password-protected. Provide the password via X-Url-Password header."));
            }
            if (!passwordEncoder.matches(urlPassword, r.passwordHash())) {
                log.debug("Incorrect password attempt for code={}", code);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Incorrect password"));
            }
        }

        // ── Private URL — ACL check ───────────────────────────────────────────
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
            if (r.urlId() != null && !urlAccessListRepository.existsByUrlIdAndUserId(r.urlId(), userId)) {
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
