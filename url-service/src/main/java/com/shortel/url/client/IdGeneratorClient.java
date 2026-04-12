package com.shortel.url.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class IdGeneratorClient {

    private final RestClient restClient;

    public IdGeneratorClient(@Value("${id-generator.url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @SuppressWarnings("unchecked")
    public long nextId() {
        Map<String, Object> response = restClient.get()
            .uri("/api/id/next")
            .retrieve()
            .body(Map.class);
        if (response == null) throw new RuntimeException("ID Generator returned null");
        return ((Number) response.get("id")).longValue();
    }
}
