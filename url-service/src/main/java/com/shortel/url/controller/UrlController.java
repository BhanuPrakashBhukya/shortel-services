package com.shortel.url.controller;

import com.shortel.url.dto.CreateUrlRequest;
import com.shortel.url.dto.UpdateUrlRequest;
import com.shortel.url.entity.ShortenedUrl;
import com.shortel.url.service.UrlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/urls")
@RequiredArgsConstructor
public class UrlController {

    private final UrlService urlService;

    @PostMapping
    public ResponseEntity<ShortenedUrl> create(
        @Valid @RequestBody CreateUrlRequest request,
        @RequestHeader(value = "X-Tenant-Id", required = false) String tenantIdHeader,
        @RequestHeader(value = "X-User-Id",   required = false) String userIdHeader
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(urlService.create(request, resolveTenantId(tenantIdHeader), resolveUserId(userIdHeader)));
    }

    @GetMapping("/{code}")
    public ResponseEntity<ShortenedUrl> get(
        @PathVariable String code,
        @RequestHeader(value = "X-User-Id", required = false) String userIdHeader
    ) {
        return urlService.findByCode(code)
            .map(url -> {
                if (ShortenedUrl.Visibility.PRIVATE.equals(url.getVisibility())
                        && (userIdHeader == null || userIdHeader.isBlank())) {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Authentication required to access private URL metadata");
                }
                return ResponseEntity.ok(url);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<ShortenedUrl>> list(
        @RequestHeader(value = "X-Tenant-Id", required = false) String tenantIdHeader,
        @RequestParam(required = false) Long createdBy
    ) {
        return ResponseEntity.ok(urlService.findByTenant(resolveTenantId(tenantIdHeader), createdBy));
    }

    @PatchMapping("/{code}")
    public ResponseEntity<ShortenedUrl> update(
        @PathVariable String code,
        @Valid @RequestBody UpdateUrlRequest request,
        @RequestHeader(value = "X-Tenant-Id", required = false) String tenantIdHeader,
        @RequestHeader(value = "X-User-Id",   required = false) String userIdHeader
    ) {
        return ResponseEntity.ok(
            urlService.update(code, request, resolveTenantId(tenantIdHeader), resolveUserId(userIdHeader)));
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Map<String, String>> deactivate(
        @PathVariable String code,
        @RequestHeader(value = "X-Tenant-Id", required = false) String tenantIdHeader,
        @RequestHeader(value = "X-User-Id",   required = false) String userIdHeader
    ) {
        urlService.deactivate(code, resolveTenantId(tenantIdHeader), resolveUserId(userIdHeader));
        return ResponseEntity.ok(Map.of("status", "deactivated", "code", code));
    }

    // ── Header helpers ──────────────────────────────────────────────────────

    private Long resolveTenantId(String header) {
        if (header == null || header.isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Missing X-Tenant-Id — request must come through the gateway");
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid X-Tenant-Id: " + header);
        }
    }

    private Long resolveUserId(String header) {
        if (header == null || header.isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Missing X-User-Id — request must come through the gateway");
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid X-User-Id: " + header);
        }
    }
}
