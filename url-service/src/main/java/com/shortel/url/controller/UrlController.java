package com.shortel.url.controller;

import com.shortel.url.dto.CreateUrlRequest;
import com.shortel.url.entity.ShortenedUrl;
import com.shortel.url.service.UrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/urls")
@RequiredArgsConstructor
public class UrlController {

    private final UrlService urlService;

    @PostMapping
    public ResponseEntity<ShortenedUrl> create(@RequestBody CreateUrlRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(urlService.create(request));
    }

    @GetMapping("/{code}")
    public ResponseEntity<ShortenedUrl> get(@PathVariable String code) {
        return urlService.findByCode(code)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<ShortenedUrl>> list(
        @RequestParam(defaultValue = "1") Long tenantId
    ) {
        return ResponseEntity.ok(urlService.findByTenant(tenantId));
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Map<String, String>> deactivate(@PathVariable String code) {
        urlService.deactivate(code);
        return ResponseEntity.ok(Map.of("status", "deactivated", "code", code));
    }
}
