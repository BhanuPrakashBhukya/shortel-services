package com.shortel.url.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateUrlRequest {
    private String longUrl;
    private String customAlias;
    private String visibility;  // "public" or "private"
    private LocalDateTime expiresAt;
    private Long tenantId;
    private Long createdBy;
}
