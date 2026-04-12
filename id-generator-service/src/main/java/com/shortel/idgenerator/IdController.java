package com.shortel.idgenerator;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/id")
@RequiredArgsConstructor
public class IdController {

    private final SnowflakeIdGenerator idGenerator;

    @GetMapping("/next")
    public Map<String, Long> nextId() {
        return Map.of("id", idGenerator.nextId());
    }

    @GetMapping("/batch")
    public Map<String, List<Long>> batchIds(@RequestParam(defaultValue = "10") int count) {
        if (count < 1 || count > 1000) {
            throw new IllegalArgumentException("Batch size must be between 1 and 1000");
        }
        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(idGenerator.nextId());
        }
        return Map.of("ids", ids);
    }
}
