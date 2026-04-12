package com.shortel.redirect.controller;

import com.shortel.redirect.entity.ShortenedUrl;
import com.shortel.redirect.service.ClickEventProducer;
import com.shortel.redirect.service.RedirectService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class RedirectController {

    private final RedirectService    redirectService;
    private final ClickEventProducer clickEventProducer;

    /**
     * Core redirect endpoint — the hot path.
     * Cache-first. Async click event. Never writes to DB synchronously.
     */
    @GetMapping("/{code:[A-Za-z0-9]{4,10}}")
    public ResponseEntity<Void> redirect(
        @PathVariable String code,
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

        if (r.visibility() == ShortenedUrl.Visibility.PRIVATE) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
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
            "shortCode",  code,
            "longUrl",    r.longUrl(),
            "visibility", r.visibility().name(),
            "expired",    r.expired()
        ));
    }
}
