package com.shortel.tenant.controller;

import com.shortel.tenant.entity.Tenant;
import com.shortel.tenant.repository.TenantRepository;
import com.shortel.tenant.service.QuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantRepository tenantRepository;
    private final QuotaService quotaService;

    @PostMapping
    public ResponseEntity<Tenant> create(@RequestBody Tenant tenant) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tenantRepository.save(tenant));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Tenant> get(@PathVariable Long id) {
        return tenantRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/quota")
    public ResponseEntity<Map<String, Object>> quota(@PathVariable Long id) {
        return tenantRepository.findById(id)
            .map(t -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("tenantId",    id);
                data.put("plan",        t.getPlan().name());
                data.put("urlsUsed",    quotaService.getUrlsUsed(id));
                data.put("urlsLimit",   t.getUrlQuota() == Long.MAX_VALUE ? "unlimited" : String.valueOf(t.getUrlQuota()));
                data.put("clicksUsed",  quotaService.getClicksUsed(id));
                data.put("clicksLimit", t.getClickQuota() == Long.MAX_VALUE ? "unlimited" : String.valueOf(t.getClickQuota()));
                return ResponseEntity.ok(data);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/quota/check-url")
    public ResponseEntity<Map<String, Object>> checkUrlQuota(@PathVariable Long id) {
        boolean allowed = quotaService.checkUrlQuota(id);
        return ResponseEntity.ok(Map.of("tenantId", id, "allowed", allowed, "resource", "URL_CREATION"));
    }

    @PostMapping("/{id}/quota/increment-url")
    public ResponseEntity<Map<String, Long>> incrementUrl(@PathVariable Long id) {
        long used = quotaService.incrementUrls(id);
        return ResponseEntity.ok(Map.of("tenantId", id, "urlsUsed", used));
    }

    @PostMapping("/{id}/quota/increment-click")
    public ResponseEntity<Map<String, Long>> incrementClick(@PathVariable Long id) {
        long used = quotaService.incrementClicks(id);
        return ResponseEntity.ok(Map.of("tenantId", id, "clicksUsed", used));
    }
}
