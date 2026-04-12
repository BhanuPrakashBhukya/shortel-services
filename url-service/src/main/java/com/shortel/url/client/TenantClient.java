package com.shortel.url.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Slf4j
@Component
public class TenantClient {

    private final RestClient restClient;

    public TenantClient(@Value("${tenant-service.url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @SuppressWarnings("unchecked")
    public boolean checkUrlQuota(Long tenantId) {
        try {
            Map<String, Object> resp = restClient.post()
                .uri("/api/v1/tenants/{id}/quota/check-url", tenantId)
                .retrieve()
                .body(Map.class);
            return resp != null && Boolean.TRUE.equals(resp.get("allowed"));
        } catch (RestClientException e) {
            log.warn("Tenant service quota check failed, allowing by default: {}", e.getMessage());
            return true; // Fail-open for quota (can be changed to fail-closed)
        }
    }

    public void incrementUrlUsage(Long tenantId) {
        try {
            restClient.post()
                .uri("/api/v1/tenants/{id}/quota/increment-url", tenantId)
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Failed to increment URL quota for tenant {}: {}", tenantId, e.getMessage());
        }
    }
}
